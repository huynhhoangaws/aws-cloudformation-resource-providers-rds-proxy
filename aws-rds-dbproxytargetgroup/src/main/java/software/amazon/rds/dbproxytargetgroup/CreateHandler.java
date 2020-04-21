package software.amazon.rds.dbproxytargetgroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.AmazonRDSClientBuilder;
import com.amazonaws.services.rds.model.ConnectionPoolConfiguration;
import com.amazonaws.services.rds.model.DBProxy;
import com.amazonaws.services.rds.model.DBProxyNotFoundException;
import com.amazonaws.services.rds.model.DBProxyTarget;
import com.amazonaws.services.rds.model.DBProxyTargetGroup;
import com.amazonaws.services.rds.model.DescribeDBProxiesRequest;
import com.amazonaws.services.rds.model.DescribeDBProxiesResult;
import com.amazonaws.services.rds.model.DescribeDBProxyTargetGroupsRequest;
import com.amazonaws.services.rds.model.ModifyDBProxyTargetGroupRequest;
import com.amazonaws.services.rds.model.RegisterDBProxyTargetsRequest;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;

public class CreateHandler extends BaseHandler<CallbackContext> {
    public static final String TIMED_OUT_MESSAGE = "Timed out waiting for target group to become available.";

    private AmazonWebServicesClientProxy clientProxy;
    private AmazonRDS rdsClient;

    @Override
    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final Logger logger) {
        final ResourceModel model = request.getDesiredResourceState();

        clientProxy = proxy;
        rdsClient = AmazonRDSClientBuilder.defaultClient();

        final CallbackContext currentContext = Optional.ofNullable(callbackContext)
                                                       .orElse(CallbackContext.builder()
                                                                              .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                                              .build());

        // This Lambda will continually be re-invoked with the current state of the proxy, finally succeeding when state stabilizes.
        return createTargetGroupAndUpdateProgress(model, currentContext);
    }

    private ProgressEvent<ResourceModel, CallbackContext> createTargetGroupAndUpdateProgress(ResourceModel model,
                                                                                             CallbackContext callbackContext) {
        // This Lambda will continually be re-invoked with the current state of the proxy, finally succeeding when state stabilizes.
        final DBProxy proxyStateSoFar = callbackContext.getProxy();

        if (callbackContext.getStabilizationRetriesRemaining() == 0) {
            throw new RuntimeException(TIMED_OUT_MESSAGE);
        }

        if (proxyStateSoFar != null && !proxyStateSoFar.getStatus().equals(Constants.DELETING_PROXY_STATE)) {
            if (callbackContext.getTargetGroupStatus() == null) {
                // If proxy is in the available state then modify the target group settings
                DBProxyTargetGroup targetGroupSettings = modifyProxyTargetGroup(model);
                return ProgressEvent.<ResourceModel, CallbackContext>builder()
                               .resourceModel(model)
                               .status(OperationStatus.IN_PROGRESS)
                               .callbackContext(CallbackContext.builder()
                                                               .proxy(proxyStateSoFar)
                                                               .targetGroupStatus(targetGroupSettings)
                                                               .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                               .build())
                               .build();

            } else {
                model.setTargetGroupArn(callbackContext.getTargetGroupStatus().getTargetGroupArn());
                if (callbackContext.getTargets() == null) {
                    //If targets have not been setup, register them
                    List<DBProxyTarget> targets = registerDefaultTarget(model);
                    return ProgressEvent.<ResourceModel, CallbackContext>builder()
                                   .resourceModel(model)
                                   .status(OperationStatus.IN_PROGRESS)
                                   .callbackContext(CallbackContext.builder()
                                                                   .proxy(proxyStateSoFar)
                                                                   .targetGroupStatus(callbackContext.getTargetGroupStatus())
                                                                   .targets(targets)
                                                                   .stabilizationRetriesRemaining(Constants.NUMBER_OF_STATE_POLL_RETRIES)
                                                                   .build())
                                   .build();
                } else {
                    //All setup has been completed
                    return ProgressEvent.<ResourceModel, CallbackContext>builder()
                                   .resourceModel(model)
                                   .status(OperationStatus.SUCCESS)
                                   .build();
                }
            }
        } else {
            // Wait for proxy to be in active state
            try {
                Thread.sleep(Constants.POLL_RETRY_DELAY_IN_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return ProgressEvent.<ResourceModel, CallbackContext>builder()
                           .resourceModel(model)
                           .status(OperationStatus.IN_PROGRESS)
                           .callbackContext(CallbackContext.builder()
                                                           .proxy(describeProxyStatus(model.getDBProxyName()))
                                                           .stabilizationRetriesRemaining(callbackContext.getStabilizationRetriesRemaining() - 1)
                                                           .build())
                           .build();
        }
    }

    private DBProxyTargetGroup modifyProxyTargetGroup(ResourceModel model) {
        ConnectionPoolConfigurationInfoFormat modelConnectionPoolConfig = model.getConnectionPoolConfigurationInfo();

        if (modelConnectionPoolConfig == null) {
            DescribeDBProxyTargetGroupsRequest describeRequest = new DescribeDBProxyTargetGroupsRequest()
                                                                         .withDBProxyName(model.getDBProxyName())
                                                                         .withTargetGroupName(model.getTargetGroupName());
            return clientProxy.injectCredentialsAndInvoke(describeRequest, rdsClient::describeDBProxyTargetGroups).getTargetGroups().get(0);
        }

        ConnectionPoolConfiguration connectionPoolConfiguration =
                new ConnectionPoolConfiguration()
                        .withMaxConnectionsPercent(modelConnectionPoolConfig.getMaxConnectionsPercent())
                        .withMaxIdleConnectionsPercent(modelConnectionPoolConfig.getMaxIdleConnectionsPercent())
                        .withConnectionBorrowTimeout(modelConnectionPoolConfig.getConnectionBorrowTimeout())
                        .withSessionPinningFilters(modelConnectionPoolConfig.getSessionPinningFilters())
                        .withInitQuery(modelConnectionPoolConfig.getInitQuery());

        ModifyDBProxyTargetGroupRequest request = new ModifyDBProxyTargetGroupRequest()
                                                          .withDBProxyName(model.getDBProxyName())
                                                          .withTargetGroupName(model.getTargetGroupName())
                                                          .withConnectionPoolConfig(connectionPoolConfiguration);

        return clientProxy.injectCredentialsAndInvoke(request, rdsClient::modifyDBProxyTargetGroup).getDBProxyTargetGroup();
    }

    private List<DBProxyTarget> registerDefaultTarget(ResourceModel model) {
        List<String> newClusters = Utility.getClusters(model);
        List<String> newInstances = Utility.getInstances(model);

        if (newClusters.size() == 0 && newInstances.size() == 0) {
            return new ArrayList<>();
        }

        RegisterDBProxyTargetsRequest registerRequest = new RegisterDBProxyTargetsRequest()
                                                                .withDBProxyName(model.getDBProxyName())
                                                                .withTargetGroupName(model.getTargetGroupName())
                                                                .withDBClusterIdentifiers(newClusters)
                                                                .withDBInstanceIdentifiers(newInstances);

        return clientProxy.injectCredentialsAndInvoke(registerRequest, rdsClient::registerDBProxyTargets).getDBProxyTargets();
    }

    private DBProxy describeProxyStatus(String proxyName) {
        DescribeDBProxiesRequest describeDBProxiesRequest;
        DescribeDBProxiesResult describeDBProxiesResult;

        describeDBProxiesRequest = new DescribeDBProxiesRequest().withDBProxyName(proxyName);
        try {
            describeDBProxiesResult = clientProxy.injectCredentialsAndInvoke(describeDBProxiesRequest, rdsClient::describeDBProxies);
            return describeDBProxiesResult.getDBProxies()
                                          .stream()
                                          .findFirst()
                                          .orElse(new DBProxy());
        } catch (DBProxyNotFoundException e) {
            // Proxy not found, possible out of order creation, will retry
            return null;
        }
    }
}
