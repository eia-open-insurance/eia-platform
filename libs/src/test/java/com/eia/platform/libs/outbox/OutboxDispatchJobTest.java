package com.eia.platform.libs.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxDispatchJobTest {
    @Mock
    private OutboxDispatchService dispatchService;

    @Mock
    private JobExecutionContext context;

    @Test
    void delegatesTheBatchToTheInjectedService() throws Exception {
        OutboxDispatchJob job = new OutboxDispatchJob();
        job.setDispatchService(dispatchService);

        job.execute(context);

        verify(dispatchService).dispatchBatch();
    }

    @Test
    void fallsBackToTheSchedulerContext() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SchedulerContext schedulerContext = new SchedulerContext();
        schedulerContext.put(OutboxDispatchJob.SERVICE_CONTEXT_KEY, dispatchService);
        when(context.getScheduler()).thenReturn(scheduler);
        when(scheduler.getContext()).thenReturn(schedulerContext);

        OutboxDispatchJob job = new OutboxDispatchJob();
        job.execute(context);

        verify(dispatchService).dispatchBatch();
    }
}
