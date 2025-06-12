package pt.ulisboa.tecnico.cnv.dynamodbclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.util.TableUtils;

/**
 * This class handles logic for writing to DynamoDB
 */
public class DynamoDBWriter {

    private static String AWS_REGION = "eu-west-1";
    private static String ongoingRequestsTableName = "CNVOngoingRequests";
    private static String metricsTableName = "CNVRequestMetrics";

    private static AmazonDynamoDB dynamoDB;

    private static final DynamoDBWriter instance = new DynamoDBWriter();

    private DynamoDBWriter() {
        dynamoDB = AmazonDynamoDBClientBuilder.standard()
            .withCredentials(new EnvironmentVariableCredentialsProvider())
            .withRegion(AWS_REGION)
            .build();
    }

    public static DynamoDBWriter getInstance() {
        return instance;
    }

    /* Creates the DynamoDB table if it doesn't exist */
    public static void setup() {
        try {
            // Create metrics table
            CreateTableRequest createMetricsTableRequest = new CreateTableRequest().withTableName(metricsTableName)
                .withKeySchema(new KeySchemaElement().withAttributeName("args").withKeyType(KeyType.HASH))
                .withAttributeDefinitions(new AttributeDefinition().withAttributeName("args").withAttributeType(ScalarAttributeType.S))
                .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

            TableUtils.createTableIfNotExists(dynamoDB, createMetricsTableRequest);

            // Create ongoing requests table
            CreateTableRequest createOngoingTableRequest = new CreateTableRequest().withTableName(ongoingRequestsTableName)
                .withKeySchema(new KeySchemaElement().withAttributeName("instanceId").withKeyType(KeyType.HASH),
                               new KeySchemaElement().withAttributeName("requestId").withKeyType(KeyType.RANGE))
                .withAttributeDefinitions(new AttributeDefinition().withAttributeName("instanceId").withAttributeType(ScalarAttributeType.S),
                                          new AttributeDefinition().withAttributeName("requestId").withAttributeType(ScalarAttributeType.S))
                .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

            TableUtils.createTableIfNotExists(dynamoDB, createOngoingTableRequest);
            
            // Wait for the tables to move into ACTIVE state
            TableUtils.waitUntilActive(dynamoDB, metricsTableName);
            TableUtils.waitUntilActive(dynamoDB, ongoingRequestsTableName);
            
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
            
        } catch (InterruptedException ie) {
            System.out.println("Caught InterruptedException while waiting for DynamoDB table");
        }
    }

    /* Creates a new item for the CNVRequestMetrics table */
    private Map<String, AttributeValue> newMetricsItem(String args, String handler, long nInstructions, long nMemAccesses) {
        Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("args", new AttributeValue(args));
        item.put("handler", new AttributeValue(handler));
        item.put("nInstructions", new AttributeValue().withN(String.valueOf(nInstructions)));
        item.put("nMemAccesses", new AttributeValue().withN(String.valueOf(nMemAccesses)));
        return item;
    }

    /* Writes finished request's arguments and execution metrics to the CNVRequestMetrics table */
    public void writeMetrics(String args, String handler, long instructions, long memAccesses) {
        try {
            dynamoDB.putItem(new PutItemRequest(metricsTableName, newMetricsItem(args, handler, instructions, memAccesses)));
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }

    /* Creates a new item for the CNVOngoingRequests table */
    private Map<String, AttributeValue> newOngoingItem(String instanceId, String requestId, long nInstructions, long nMemAccesses) {
        Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("instanceId", new AttributeValue(instanceId));
        item.put("requestId", new AttributeValue(requestId));
        item.put("nInstructions", new AttributeValue().withN(String.valueOf(nInstructions)));
        item.put("nMemAccesses", new AttributeValue().withN(String.valueOf(nMemAccesses)));
        return item;
    }

    /* Writes new request's instanceId, requestId, and (possibly estimated) metrics to the CNVOngoingRequests table */
    public void registerNewRequest(String instanceId, String requestId, long instructions, long memAccesses) {
        // Note: "instructions" and "memAccesses" may be estimations!
        try {
            dynamoDB.putItem(new PutItemRequest(ongoingRequestsTableName, newOngoingItem(instanceId, requestId, instructions, memAccesses)));
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }

    /* Removes a newly finished request from the ongoing requests table */
    public void deregisterRequest(String instanceId, String requestId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("instanceId", new AttributeValue(instanceId));
        key.put("requestId", new AttributeValue(requestId));

        try {
            DeleteItemRequest deleteRequest = new DeleteItemRequest().withTableName(ongoingRequestsTableName).withKey(key);

            dynamoDB.deleteItem(deleteRequest);
            
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }

    /* Returns the metrics for a set of arguments, if a request with the same args has been received before */
    public long[] getRequestMetrics(String args) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("args", new AttributeValue().withS(args));

            GetItemRequest request = new GetItemRequest().withTableName(metricsTableName).withKey(key);

            GetItemResult result = dynamoDB.getItem(request);
            Map<String, AttributeValue> item = result.getItem();

            if (item == null || item.isEmpty()) {
                return null;
            }

            long nInstructions = Long.parseLong(item.get("nInstructions").getN());
            long nMemAccesses = Long.parseLong(item.get("nMemAccesses").getN());

            return new long[]{nInstructions, nMemAccesses};

        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());

            return null;
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());

            return null;
        }
    }

    /* Returns all info on ongoing requests, for usage by the load balancer */
    public List<Map<String, AttributeValue>> getAllOngoingRequestMetrics() {
        List<Map<String, AttributeValue>> allItems = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        
        try {
            do {
                ScanRequest scanRequest = new ScanRequest().withTableName(ongoingRequestsTableName).withExclusiveStartKey(lastKey);

                ScanResult result = dynamoDB.scan(scanRequest);
                allItems.addAll(result.getItems());
                lastKey = result.getLastEvaluatedKey();
            } while (lastKey != null);

            return allItems;
            
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());

            return null;
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());

            return null;
        }
    }

    public List<Map<String, AttributeValue>> getHandlerRequestMetrics(String handler) {
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":h", new AttributeValue().withS(handler));

        HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
        Condition condition = new Condition()
            .withComparisonOperator(ComparisonOperator.EQ.toString())
            .withAttributeValueList(new AttributeValue().withS(handler));
        scanFilter.put("handler", condition);

        try {
            ScanRequest scanRequest = new ScanRequest(metricsTableName).withScanFilter(scanFilter);
            ScanResult scanResult = dynamoDB.scan(scanRequest);
            return scanResult.getItems();

        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());

            return Collections.emptyList();
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());

            return Collections.emptyList();
        }
    }

    public boolean isInstanceIdle(String instanceId) {
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":id", new AttributeValue().withS(instanceId));

        try {
            QueryRequest queryRequest = new QueryRequest()
                .withTableName(ongoingRequestsTableName)
                .withKeyConditionExpression("instanceId = :id")
                .withExpressionAttributeValues(expressionAttributeValues)
                .withLimit(1); // Check if *any* entry exists

            QueryResult result = dynamoDB.query(queryRequest);

            return result.getCount() == 0;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());

            return false;
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());

            return false;
        }
    }

    public void cleanUpOngoingRequests(ConcurrentMap<String, String> activeRequests) {
        for (Map.Entry<String, String> entry : activeRequests.entrySet()) {
            deregisterRequest(entry.getValue(), entry.getKey());
        }
    }

}
