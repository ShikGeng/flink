/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.webmonitor;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.execution.CheckpointType;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.checkpoint.CheckpointStatsSnapshot;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.dispatcher.DispatcherGateway;
import org.apache.flink.runtime.dispatcher.DispatcherId;
import org.apache.flink.runtime.dispatcher.TriggerSavepointMode;
import org.apache.flink.runtime.executiongraph.ArchivedExecutionGraph;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.messages.webmonitor.ClusterOverview;
import org.apache.flink.runtime.messages.webmonitor.MultipleJobsDetails;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.runtime.rest.handler.async.OperationResult;
import org.apache.flink.runtime.rest.handler.job.AsynchronousJobOperationKey;
import org.apache.flink.runtime.rest.messages.ThreadDumpInfo;
import org.apache.flink.runtime.rpc.RpcTimeout;
import org.apache.flink.runtime.scheduler.ExecutionGraphInfo;
import org.apache.flink.streaming.api.graph.ExecutionPlan;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.concurrent.FutureUtils;
import org.apache.flink.util.function.TriFunction;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Testing implementation of the {@link DispatcherGateway}. */
public final class TestingDispatcherGateway extends TestingRestfulGateway
        implements DispatcherGateway {

    static final Function<ExecutionPlan, CompletableFuture<Acknowledge>> DEFAULT_SUBMIT_FUNCTION =
            jobGraph -> CompletableFuture.completedFuture(Acknowledge.get());
    static final TriFunction<JobID, String, Throwable, CompletableFuture<Acknowledge>>
            DEFAULT_SUBMIT_FAILED_FUNCTION =
                    (jobId, jobName, Throwable) ->
                            CompletableFuture.completedFuture(Acknowledge.get());
    static final Supplier<CompletableFuture<Collection<JobID>>> DEFAULT_LIST_FUNCTION =
            () -> CompletableFuture.completedFuture(Collections.emptyList());
    static final int DEFAULT_BLOB_SERVER_PORT = 1234;
    static final DispatcherId DEFAULT_FENCING_TOKEN = DispatcherId.generate();
    static final Function<JobID, CompletableFuture<ArchivedExecutionGraph>>
            DEFAULT_REQUEST_ARCHIVED_JOB_FUNCTION =
                    jobID -> CompletableFuture.completedFuture(null);
    static final Function<ApplicationStatus, CompletableFuture<Acknowledge>>
            DEFAULT_SHUTDOWN_WITH_STATUS_FUNCTION =
                    status -> CompletableFuture.completedFuture(Acknowledge.get());
    static final TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            DEFAULT_TRIGGER_SAVEPOINT_AND_GET_LOCATION_FUNCTION =
                    (JobID jobId, String targetDirectory, SavepointFormatType formatType) ->
                            FutureUtils.completedExceptionally(new UnsupportedOperationException());
    static final TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            DEFAULT_STOP_WITH_SAVEPOINT_AND_GET_LOCATION_FUNCTION =
                    (JobID jobId, String targetDirectory, SavepointFormatType formatType) ->
                            FutureUtils.completedExceptionally(new UnsupportedOperationException());

    static final BiFunction<JobID, CheckpointType, CompletableFuture<Long>>
            DEFAULT_TRIGGER_CHECHPOINT_AND_GET_CHECKPOINT_ID_FUNCTION =
                    (JobID jobId, CheckpointType checkpointType) ->
                            FutureUtils.completedExceptionally(new UnsupportedOperationException());

    private final Function<ExecutionPlan, CompletableFuture<Acknowledge>> submitFunction;
    private final TriFunction<JobID, String, Throwable, CompletableFuture<Acknowledge>>
            submitFailedFunction;
    private final Supplier<CompletableFuture<Collection<JobID>>> listFunction;
    private final int blobServerPort;
    private final DispatcherId fencingToken;
    private final Function<JobID, CompletableFuture<ArchivedExecutionGraph>>
            requestArchivedJobFunction;
    private final Function<ApplicationStatus, CompletableFuture<Acknowledge>>
            clusterShutdownWithStatusFunction;
    private final TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            triggerSavepointAndGetLocationFunction;
    private final TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            stopWithSavepointAndGetLocationFunction;

    private final BiFunction<JobID, CheckpointType, CompletableFuture<Long>>
            triggerCheckpointAndGetCheckpointIdFunction;

    public TestingDispatcherGateway() {
        super();
        submitFunction = DEFAULT_SUBMIT_FUNCTION;
        submitFailedFunction = DEFAULT_SUBMIT_FAILED_FUNCTION;
        listFunction = DEFAULT_LIST_FUNCTION;
        blobServerPort = DEFAULT_BLOB_SERVER_PORT;
        fencingToken = DEFAULT_FENCING_TOKEN;
        requestArchivedJobFunction = DEFAULT_REQUEST_ARCHIVED_JOB_FUNCTION;
        clusterShutdownWithStatusFunction = DEFAULT_SHUTDOWN_WITH_STATUS_FUNCTION;
        triggerSavepointAndGetLocationFunction =
                DEFAULT_TRIGGER_SAVEPOINT_AND_GET_LOCATION_FUNCTION;
        stopWithSavepointAndGetLocationFunction =
                DEFAULT_STOP_WITH_SAVEPOINT_AND_GET_LOCATION_FUNCTION;
        triggerCheckpointAndGetCheckpointIdFunction =
                DEFAULT_TRIGGER_CHECHPOINT_AND_GET_CHECKPOINT_ID_FUNCTION;
    }

    public TestingDispatcherGateway(
            String address,
            String hostname,
            Function<JobID, CompletableFuture<Acknowledge>> cancelJobFunction,
            Function<JobID, CompletableFuture<ArchivedExecutionGraph>> requestJobFunction,
            Function<JobID, CompletableFuture<ExecutionGraphInfo>>
                    requestExecutionGraphInfoFunction,
            Function<JobID, CompletableFuture<CheckpointStatsSnapshot>>
                    requestCheckpointStatsSnapshotFunction,
            Function<JobID, CompletableFuture<JobResult>> requestJobResultFunction,
            Function<JobID, CompletableFuture<JobStatus>> requestJobStatusFunction,
            Supplier<CompletableFuture<MultipleJobsDetails>> requestMultipleJobDetailsSupplier,
            Supplier<CompletableFuture<ClusterOverview>> requestClusterOverviewSupplier,
            Supplier<CompletableFuture<Collection<String>>>
                    requestMetricQueryServiceAddressesSupplier,
            Supplier<CompletableFuture<Collection<Tuple2<ResourceID, String>>>>
                    requestTaskManagerMetricQueryServiceGatewaysSupplier,
            Supplier<CompletableFuture<ThreadDumpInfo>> requestThreadDumpSupplier,
            BiFunction<AsynchronousJobOperationKey, CheckpointType, CompletableFuture<Acknowledge>>
                    triggerCheckpointFunction,
            Function<AsynchronousJobOperationKey, CompletableFuture<OperationResult<Long>>>
                    getCheckpointStatusFunction,
            TriFunction<
                            AsynchronousJobOperationKey,
                            String,
                            SavepointFormatType,
                            CompletableFuture<Acknowledge>>
                    triggerSavepointFunction,
            TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                    triggerSavepointAndGetLocationFunction,
            TriFunction<
                            AsynchronousJobOperationKey,
                            String,
                            SavepointFormatType,
                            CompletableFuture<Acknowledge>>
                    stopWithSavepointFunction,
            TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                    stopWithSavepointAndGetLocationFunction,
            Function<AsynchronousJobOperationKey, CompletableFuture<OperationResult<String>>>
                    getSavepointStatusFunction,
            BiFunction<JobID, CheckpointType, CompletableFuture<Long>>
                    triggerCheckpointAndGetCheckpointIdFunction,
            Function<ExecutionPlan, CompletableFuture<Acknowledge>> submitFunction,
            TriFunction<JobID, String, Throwable, CompletableFuture<Acknowledge>>
                    submitFailedFunction,
            Supplier<CompletableFuture<Collection<JobID>>> listFunction,
            int blobServerPort,
            DispatcherId fencingToken,
            Function<JobID, CompletableFuture<ArchivedExecutionGraph>> requestArchivedJobFunction,
            Supplier<CompletableFuture<Acknowledge>> clusterShutdownSupplier,
            Function<ApplicationStatus, CompletableFuture<Acknowledge>>
                    clusterShutdownWithStatusFunction,
            TriFunction<
                            JobID,
                            String,
                            SerializedValue<CoordinationRequest>,
                            CompletableFuture<CoordinationResponse>>
                    deliverCoordinationRequestToCoordinatorFunction) {
        super(
                address,
                hostname,
                cancelJobFunction,
                requestJobFunction,
                requestExecutionGraphInfoFunction,
                requestCheckpointStatsSnapshotFunction,
                requestJobResultFunction,
                requestJobStatusFunction,
                requestMultipleJobDetailsSupplier,
                requestClusterOverviewSupplier,
                requestMetricQueryServiceAddressesSupplier,
                requestTaskManagerMetricQueryServiceGatewaysSupplier,
                requestThreadDumpSupplier,
                triggerCheckpointFunction,
                getCheckpointStatusFunction,
                triggerSavepointFunction,
                stopWithSavepointFunction,
                getSavepointStatusFunction,
                clusterShutdownSupplier,
                deliverCoordinationRequestToCoordinatorFunction);
        this.submitFunction = submitFunction;
        this.submitFailedFunction = submitFailedFunction;
        this.listFunction = listFunction;
        this.blobServerPort = blobServerPort;
        this.fencingToken = fencingToken;
        this.requestArchivedJobFunction = requestArchivedJobFunction;
        this.clusterShutdownWithStatusFunction = clusterShutdownWithStatusFunction;
        this.triggerSavepointAndGetLocationFunction = triggerSavepointAndGetLocationFunction;
        this.stopWithSavepointAndGetLocationFunction = stopWithSavepointAndGetLocationFunction;
        this.triggerCheckpointAndGetCheckpointIdFunction =
                triggerCheckpointAndGetCheckpointIdFunction;
    }

    @Override
    public CompletableFuture<Acknowledge> submitJob(ExecutionPlan executionPlan, Duration timeout) {
        return submitFunction.apply(executionPlan);
    }

    @Override
    public CompletableFuture<Acknowledge> submitFailedJob(
            JobID jobId, String jobName, Throwable exception) {
        return submitFailedFunction.apply(jobId, jobName, exception);
    }

    @Override
    public CompletableFuture<Collection<JobID>> listJobs(Duration timeout) {
        return listFunction.get();
    }

    @Override
    public CompletableFuture<Integer> getBlobServerPort(Duration timeout) {
        return CompletableFuture.completedFuture(blobServerPort);
    }

    @Override
    public DispatcherId getFencingToken() {
        return DEFAULT_FENCING_TOKEN;
    }

    public CompletableFuture<ArchivedExecutionGraph> requestJob(
            JobID jobId, @RpcTimeout Duration timeout) {
        return requestArchivedJobFunction.apply(jobId);
    }

    @Override
    public CompletableFuture<Acknowledge> shutDownCluster(ApplicationStatus applicationStatus) {
        return clusterShutdownWithStatusFunction.apply(applicationStatus);
    }

    @Override
    public CompletableFuture<String> triggerSavepointAndGetLocation(
            JobID jobId,
            String targetDirectory,
            SavepointFormatType formatType,
            TriggerSavepointMode savepointMode,
            Duration timeout) {
        return triggerSavepointAndGetLocationFunction.apply(jobId, targetDirectory, formatType);
    }

    @Override
    public CompletableFuture<String> stopWithSavepointAndGetLocation(
            JobID jobId,
            String targetDirectory,
            SavepointFormatType formatType,
            TriggerSavepointMode savepointMode,
            Duration timeout) {
        return stopWithSavepointAndGetLocationFunction.apply(jobId, targetDirectory, formatType);
    }

    @Override
    public CompletableFuture<Long> triggerCheckpointAndGetCheckpointID(
            final JobID jobId, final CheckpointType checkpointType, final Duration timeout) {
        return triggerCheckpointAndGetCheckpointIdFunction.apply(jobId, checkpointType);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /** Builder for the {@link TestingDispatcherGateway}. */
    public static final class Builder extends TestingRestfulGateway.AbstractBuilder<Builder> {

        private Function<ExecutionPlan, CompletableFuture<Acknowledge>> submitFunction;
        private TriFunction<JobID, String, Throwable, CompletableFuture<Acknowledge>>
                submitFailedFunction;
        private Supplier<CompletableFuture<Collection<JobID>>> listFunction;
        private int blobServerPort;
        private DispatcherId fencingToken;
        private Function<JobID, CompletableFuture<ArchivedExecutionGraph>>
                requestArchivedJobFunction;
        private Function<ApplicationStatus, CompletableFuture<Acknowledge>>
                clusterShutdownWithStatusFunction = DEFAULT_SHUTDOWN_WITH_STATUS_FUNCTION;
        private TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                triggerSavepointAndGetLocationFunction;
        private TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                stopWithSavepointAndGetLocationFunction;

        private BiFunction<JobID, CheckpointType, CompletableFuture<Long>>
                triggerCheckpointAndGetCheckpointIdFunction;

        private Builder() {
            // No-op.
        }

        public Builder setSubmitFunction(
                Function<ExecutionPlan, CompletableFuture<Acknowledge>> submitFunction) {
            this.submitFunction = submitFunction;
            return this;
        }

        public Builder setSubmitFailedFunction(
                TriFunction<JobID, String, Throwable, CompletableFuture<Acknowledge>>
                        submitFailedFunction) {
            this.submitFailedFunction = submitFailedFunction;
            return this;
        }

        public Builder setListFunction(
                Supplier<CompletableFuture<Collection<JobID>>> listFunction) {
            this.listFunction = listFunction;
            return this;
        }

        public Builder setRequestArchivedJobFunction(
                Function<JobID, CompletableFuture<ArchivedExecutionGraph>> requestJobFunction) {
            requestArchivedJobFunction = requestJobFunction;
            return this;
        }

        public Builder setClusterShutdownFunction(
                Function<ApplicationStatus, CompletableFuture<Acknowledge>>
                        clusterShutdownFunction) {
            this.clusterShutdownWithStatusFunction = clusterShutdownFunction;
            return this;
        }

        @Override
        public Builder setRequestJobFunction(
                Function<JobID, CompletableFuture<ArchivedExecutionGraph>> requestJobFunction) {
            // signature clash
            throw new UnsupportedOperationException("Use setRequestArchivedJobFunction() instead.");
        }

        public Builder setTriggerSavepointAndGetLocationFunction(
                TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                        triggerSavepointAndGetLocationFunction) {
            this.triggerSavepointAndGetLocationFunction = triggerSavepointAndGetLocationFunction;
            return this;
        }

        public Builder setStopWithSavepointAndGetLocationFunction(
                TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                        stopWithSavepointAndGetLocationFunction) {
            this.stopWithSavepointAndGetLocationFunction = stopWithSavepointAndGetLocationFunction;
            return this;
        }

        public Builder setTriggerCheckpointAndGetCheckpointIdFunction(
                BiFunction<JobID, CheckpointType, CompletableFuture<Long>>
                        triggerCheckpointAndGetCheckpointIdFunction) {
            this.triggerCheckpointAndGetCheckpointIdFunction =
                    triggerCheckpointAndGetCheckpointIdFunction;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public Builder setBlobServerPort(int blobServerPort) {
            this.blobServerPort = blobServerPort;
            return this;
        }

        public Builder setFencingToken(DispatcherId fencingToken) {
            this.fencingToken = fencingToken;
            return this;
        }

        public TestingDispatcherGateway build() {
            return new TestingDispatcherGateway(
                    address,
                    hostname,
                    cancelJobFunction,
                    requestJobFunction,
                    requestExecutionGraphInfoFunction,
                    requestCheckpointStatsSnapshotFunction,
                    requestJobResultFunction,
                    requestJobStatusFunction,
                    requestMultipleJobDetailsSupplier,
                    requestClusterOverviewSupplier,
                    requestMetricQueryServiceGatewaysSupplier,
                    requestTaskManagerMetricQueryServiceGatewaysSupplier,
                    requestThreadDumpSupplier,
                    triggerCheckpointFunction,
                    getCheckpointStatusFunction,
                    triggerSavepointFunction,
                    triggerSavepointAndGetLocationFunction,
                    stopWithSavepointFunction,
                    stopWithSavepointAndGetLocationFunction,
                    getSavepointStatusFunction,
                    triggerCheckpointAndGetCheckpointIdFunction,
                    submitFunction,
                    submitFailedFunction,
                    listFunction,
                    blobServerPort,
                    fencingToken,
                    requestArchivedJobFunction,
                    clusterShutdownSupplier,
                    clusterShutdownWithStatusFunction,
                    deliverCoordinationRequestToCoordinatorFunction);
        }
    }
}
