package com.zhuque.common;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/** 进程内长任务状态。任务证据最终落业务表，进度本身允许随进程重启丢失。 */
@Component
public class JobRegistry {

    private final ConcurrentMap<String, JobProgress> jobs = new ConcurrentHashMap<>();

    public String start(int total, String firstStep) {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new JobProgress(id, Math.max(0, total), 0, requireStep(firstStep), "running", null));
        return id;
    }

    public void update(String jobId, int done, String currentStep) {
        jobs.compute(jobId, (id, old) -> {
            if (old == null) {
                throw ApiException.notFound("任务 " + jobId);
            }
            return new JobProgress(id, old.total(), Math.min(Math.max(done, 0), old.total()),
                    requireStep(currentStep), "running", null);
        });
    }

    public void done(String jobId, String finalStep) {
        jobs.compute(jobId, (id, old) -> {
            if (old == null) {
                throw ApiException.notFound("任务 " + jobId);
            }
            return new JobProgress(id, old.total(), old.total(), requireStep(finalStep), "done", null);
        });
    }

    public void fail(String jobId, String currentStep, Throwable error) {
        jobs.compute(jobId, (id, old) -> {
            if (old == null) {
                return new JobProgress(id, 0, 0, requireStep(currentStep), "failed", safeMessage(error));
            }
            return new JobProgress(id, old.total(), old.done(), requireStep(currentStep), "failed", safeMessage(error));
        });
    }

    public JobProgress get(String jobId) {
        JobProgress progress = jobs.get(jobId);
        if (progress == null) {
            throw ApiException.notFound("任务 " + jobId);
        }
        return progress;
    }

    private static String requireStep(String step) {
        return step == null || step.isBlank() ? "准备中" : step;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank()
                ? "任务失败。查看后端日志定位具体条目后重试"
                : message;
    }
}
