package com.nflabs.zeppelin.scheduler;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nflabs.zeppelin.interpreter.remote.RemoteInterpreterProcess;
import com.nflabs.zeppelin.interpreter.thrift.RemoteInterpreterService.Client;
import com.nflabs.zeppelin.scheduler.Job.Status;

/**
 *
 */
public class RemoteScheduler implements Scheduler {
  Logger logger = LoggerFactory.getLogger(RemoteScheduler.class);

  List<Job> queue = new LinkedList<Job>();
  List<Job> running = new LinkedList<Job>();
  private ExecutorService executor;
  private SchedulerListener listener;
  boolean terminate = false;
  private String name;
  private int maxConcurrency;
  private RemoteInterpreterProcess interpreterProcess;

  public RemoteScheduler(String name, ExecutorService executor,
      RemoteInterpreterProcess interpreterProcess, SchedulerListener listener,
      int maxConcurrency) {
    this.name = name;
    this.executor = executor;
    this.listener = listener;
    this.interpreterProcess = interpreterProcess;
    this.maxConcurrency = maxConcurrency;
  }

  @Override
  public void run() {
    synchronized (queue) {
      while (terminate == false) {
        if (running.size() >= maxConcurrency || queue.isEmpty() == true) {
          try {
            queue.wait(500);
          } catch (InterruptedException e) {
          }
          continue;
        }

        Job job = queue.remove(0);
        running.add(job);

        // run
        Scheduler scheduler = this;
        JobRunner jobRunner = new JobRunner(scheduler, job);
        executor.execute(jobRunner);

        // wait until it is submitted to the remote
        synchronized (job) {
          while (!jobRunner.isJobSubmittedInRemote()) {
            try {
              job.wait(200);
            } catch (InterruptedException e) {
            }
          }
        }
      }
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Collection<Job> getJobsWaiting() {
    return null;
  }

  @Override
  public Collection<Job> getJobsRunning() {
    return null;
  }

  @Override
  public void submit(Job job) {
    job.setStatus(Status.PENDING);

    synchronized (queue) {
      queue.add(job);
      queue.notify();
    }
  }

  public void setMaxConcurrency(int maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
    synchronized (queue) {
      queue.notify();
    }
  }

  /**
   * Role of the class is get status info from remote process from PENDING to
   * RUNNING status.
   */
  private class JobStatusPoller extends Thread {
    private long initialPeriodMsec;
    private long initialPeriodCheckIntervalMsec;
    private long checkIntervalMsec;
    private boolean terminate;
    private JobListener listener;
    private Job job;

    public JobStatusPoller(long initialPeriodMsec,
        long initialPeriodCheckIntervalMsec, long checkIntervalMsec, Job job,
        JobListener listener) {
      this.initialPeriodMsec = initialPeriodMsec;
      this.initialPeriodCheckIntervalMsec = initialPeriodCheckIntervalMsec;
      this.checkIntervalMsec = checkIntervalMsec;
      this.job = job;
      this.listener = listener;
      this.terminate = false;
    }

    @Override
    public void run() {
      long started = System.currentTimeMillis();
      while (terminate == false) {
        long current = System.currentTimeMillis();
        long interval;
        if (current - started < initialPeriodMsec) {
          interval = initialPeriodCheckIntervalMsec;
        } else {
          interval = checkIntervalMsec;
        }

        synchronized (this) {
          try {
            this.wait(interval);
          } catch (InterruptedException e) {
          }
        }


        Status newStatus = getStatus();
        if (newStatus == null) { // unknown
          continue;
        }

        if (newStatus != Status.READY && newStatus != Status.PENDING) {
          // we don't need more
          continue;
        }
      }
    }

    public void shutdown() {
      terminate = true;
      synchronized (this) {
        this.notify();
      }
    }

    public synchronized Job.Status getStatus() {
      if (interpreterProcess.referenceCount() <= 0) {
        return null;
      }

      Client client;
      try {
        client = interpreterProcess.getClient();
      } catch (Exception e) {
        logger.error("Can't get status information", e);
        return Status.FINISHED;
      }

      try {
        String statusStr = client.getStatus(job.getId());
        if ("Unknown".equals(statusStr)) {
          // not found this job in the remote schedulers.
          // maybe not submitted, maybe already finished
          listener.afterStatusChange(job, null, null);
          return null;
        }
        Status status = Status.valueOf(statusStr);
        listener.afterStatusChange(job, null, status);
        return status;
      } catch (TException e) {
        logger.error("Can't get status information", e);
        return Status.FINISHED;
      } catch (Exception e) {
        logger.error("Unknown status", e);
        return null;
      } finally {
        interpreterProcess.releaseClient(client);
      }
    }
  }

  private class JobRunner implements Runnable, JobListener {
    private Scheduler scheduler;
    private Job job;
    private boolean jobExecuted;
    boolean jobSubmittedRemotely;

    public JobRunner(Scheduler scheduler, Job job) {
      this.scheduler = scheduler;
      this.job = job;
      jobExecuted = false;
      jobSubmittedRemotely = false;
    }

    public boolean isJobSubmittedInRemote() {
      return jobSubmittedRemotely;
    }

    @Override
    public void run() {
      if (job.isAborted()) {
        job.setStatus(Status.ABORT);
        job.aborted = false;

        synchronized (queue) {
          running.remove(job);
          queue.notify();
        }

        return;
      }

      JobStatusPoller jobStatusPoller = new JobStatusPoller(1500, 100, 500,
          job, this);
      jobStatusPoller.start();

      if (listener != null) {
        listener.jobStarted(scheduler, job);
      }
      job.run();
      jobExecuted = true;

      jobStatusPoller.shutdown();

      job.setStatus(jobStatusPoller.getStatus());

      if (listener != null) {
        listener.jobFinished(scheduler, job);
      }

      // reset aborted flag to allow retry
      job.aborted = false;

      synchronized (queue) {
        running.remove(job);
        queue.notify();
      }
    }

    @Override
    public void onProgressUpdate(Job job, int progress) {
    }

    @Override
    public void beforeStatusChange(Job job, Status before, Status after) {
    }

    @Override
    public void afterStatusChange(Job job, Status before, Status after) {
      if (after == null) { // unknown. maybe before sumitted remotely, maybe already finished.
        if (jobExecuted) {
          jobSubmittedRemotely = true;
          if (job.isAborted()) {
            job.setStatus(Status.ABORT);
          } else if (job.getException() != null) {
            job.setStatus(Status.ERROR);
          } else {
            job.setStatus(Status.FINISHED);
          }
        }
        return;
      }


      // Update remoteStatus
      if (jobExecuted == false) {
        if (after == Status.FINISHED || after == Status.ABORT
            || after == Status.ERROR) {
          // it can be status of last run.
          // so not updating the remoteStatus
          return;
        } else if (after == Status.RUNNING) {
          jobSubmittedRemotely = true;
        }
      } else {
        jobSubmittedRemotely = true;
      }

      // status polled by status poller
      if (job.getStatus() != after) {
        job.setStatus(after);
      }

      synchronized (job) {
        job.notify();
      }
    }
  }

  @Override
  public void stop() {
    terminate = true;
    synchronized (queue) {
      queue.notify();
    }

  }

}