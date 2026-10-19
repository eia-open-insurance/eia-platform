package com.eia.platform.libs.outbox;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job that claims and dispatches one outbox batch.
 *
 * <p>Quartz creates the job instance through Spring Boot's bean job factory,
 * which autowires the setter. The scheduler context is a fallback for
 * deployments that build the job detail without Spring autowiring.</p>
 */
@DisallowConcurrentExecution
public class OutboxDispatchJob implements Job {
    public static final String SERVICE_CONTEXT_KEY = "eia.outbox.dispatchService";

    private OutboxDispatchService dispatchService;

    public OutboxDispatchJob() {
    }

    @Autowired(required = false)
    public void setDispatchService(OutboxDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        OutboxDispatchService service = this.dispatchService;
        if (service == null) {
            try {
                Object candidate = context.getScheduler().getContext().get(SERVICE_CONTEXT_KEY);
                if (candidate instanceof OutboxDispatchService contextual) {
                    service = contextual;
                }
            } catch (SchedulerException ex) {
                throw new JobExecutionException("Unable to read the outbox scheduler context", ex);
            }
        }
        if (service == null) {
            throw new JobExecutionException(
                    "OutboxDispatchService is unavailable; check the outbox configuration");
        }
        service.dispatchBatch();
    }
}
