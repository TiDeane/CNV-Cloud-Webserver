package pt.ulisboa.tecnico.cnv.common;

import pt.ulisboa.tecnico.cnv.javassist.tools.MetricsExtractor;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;


public class RequestLifecycleManager {

    /* Registers a new request in the ongoing requests table */
    public static void registerNewRequest(String args, String requestId, DynamoDBWriter dbWriter) {
        String instanceId = InstanceMetadata.INSTANCE_ID;

        long[] metrics = dbWriter.getRequestMetrics(args);

        if (metrics == null) {
            metrics = estimateCost(args);
        }

        dbWriter.registerNewRequest(instanceId, requestId, metrics[0], metrics[1]);
    }

    /* Removes a request from the ongoing requests table */
    public static void deregisterRequest(String requestId, DynamoDBWriter dbWriter) {
        String instanceId = InstanceMetadata.INSTANCE_ID;

        dbWriter.deregisterRequest(instanceId, requestId);
    }

    /* Stores a finished request in the request metrics table */
    public static void storeFinishedRequest(String args, String handler, String requestId, DynamoDBWriter dbWriter) {
        long nInstructions = MetricsExtractor.getInstructions();
        long nMemAccesses = MetricsExtractor.getTotalMemAccesses();

        dbWriter.writeMetrics(args, handler, nInstructions, nMemAccesses);
    }

    private static long[] estimateCost(String argsVector) {
        // TODO: develop cost estimation logic
        return new long[] {100000L, 50000L};
    }
}
