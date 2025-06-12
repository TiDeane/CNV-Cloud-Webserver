package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.*;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;

import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Instant;

public class AutoScaler {

    private Set<String> activeInstanceIds;
    private Set<String> turningOnInstanceIds;
    private Set<String> waitingForDeletionInstanceIds;

    private long lastScalingEvent = 0;

    private DynamoDBWriter dbWriter; 

    private static AmazonCloudWatch cloudWatch;
    private static AmazonEC2 ec2;

    private int currentCapacity;
    private static int minimumCapacity = 1;
    private static int maximumCapacity = 2;

    private static double SCALE_UP_THRESHOLD = 30.0;     // Average CPU > 30% → scale up
    private static double SCALE_DOWN_THRESHOLD = 10.0;   // Average CPU < 10% → scale down
    private static int COOLDOWN_PERIOD = 300;            // 5 minutes cooldown period
    private static long OBS_TIME = 1000 * 60 * 5;        // 5 minutes cloudwatch observation period

    private static final double ALPHA = -1.4726764102867243e-6;
    private static final double BETA = 6.223594986449469e-5;
    private static final double INTERCEPT = 206.2278774369248;

    private static String AWS_REGION = "eu-west-1";
    private static String AMI_ID = "ami-04a80d326673de984";        // replace with webserver AMI
    private static String INSTANCE_TYPE = "t2.micro";
    private static String KEY_NAME = "eu-west-1-keypair";          // replace with your keypair
    private static String SEC_GROUP_ID = "sg-05687660699d1fb10";   // replace with your security group

    private static final AutoScaler instance = new AutoScaler();

    private AutoScaler() {
        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();
        ec2 = AmazonEC2ClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();
        this.activeInstanceIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.turningOnInstanceIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.waitingForDeletionInstanceIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.currentCapacity = 0;
        this.dbWriter = DynamoDBWriter.getInstance();
    }

    public static AutoScaler getInstance() {
        return instance;
    }

    public void evaluateAndScale() {
        System.out.println("---------- New auto scaling cycle (30s) ----------");

        /* Terminates idle instances marked for termination */
        for (String instanceId :  new HashSet<>(waitingForDeletionInstanceIds)) {
            terminateInstanceIfIdle(instanceId);
        }

        /* Removes from activeInstanceIds any instance that was terminated unexpectedly */
        for (String instanceId : new HashSet<>(activeInstanceIds)) {
            checkInstanceActivity(instanceId);
        }

        /* Adds to activeInstanceIds any instance that was turning on */
        for (String instanceId : new HashSet<>(turningOnInstanceIds)) {
            checkInstanceActivity(instanceId);
        }

        long now = Instant.now().getEpochSecond();
        
        if (now - lastScalingEvent < COOLDOWN_PERIOD) {
            // less than 5 minutes since last scaling event
            long secondsRemaining = COOLDOWN_PERIOD - (now - lastScalingEvent);
            System.out.println("AS: AutoScaler still cooling down (" + secondsRemaining + "s left)...");
            return;
        }

        double averageCpu = getAverageCpuUtilization();
        System.out.printf("AS: Average CPU Utilization: %.2f%%\n", averageCpu);

        if (currentCapacity < minimumCapacity) { // auto scaler is just starting up
            lastScalingEvent = Instant.now().getEpochSecond();
            scaleUp();
        } else if (averageCpu > SCALE_UP_THRESHOLD && currentCapacity < maximumCapacity) {
            lastScalingEvent = Instant.now().getEpochSecond();
            scaleUp();
        } else if (averageCpu < SCALE_DOWN_THRESHOLD && currentCapacity > minimumCapacity) {
            lastScalingEvent = Instant.now().getEpochSecond();
            scaleDown();
        } else {
            System.out.println("AS: No scaling action needed.");
        }
    }

    private double getAverageCpuUtilization() {
        double totalCpu = 0.0;
        int count = 0;

        for (String instanceId : activeInstanceIds) {
            Dimension instanceDimension = new Dimension().withName("InstanceId").withValue(instanceId);
            
            GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                .withStartTime(new Date(new Date().getTime() - OBS_TIME))
                .withNamespace("AWS/EC2")
                .withPeriod(60)
                .withMetricName("CPUUtilization")
                .withStatistics("Average")
                .withDimensions(instanceDimension)
                .withEndTime(new Date());
                        
            for (Datapoint dp : cloudWatch.getMetricStatistics(request).getDatapoints()) {
                System.out.println("AS: CPU utilization for instance " + instanceId + " = " + dp.getAverage());
                totalCpu += dp.getAverage();
                count++;
            }
        }

        if (count == 0) {
            System.out.println("AS: no datapoints were found, defaulting to 20% (for no scaling)");
            return 20.0;
        } else {
            return totalCpu / count;
        }
    }

    private void scaleUp() {
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
        runInstancesRequest.withImageId(AMI_ID)
                           .withInstanceType(INSTANCE_TYPE)
                           .withMinCount(1)
                           .withMaxCount(1)
                           .withKeyName(KEY_NAME)
                           .withSecurityGroupIds(SEC_GROUP_ID);

        RunInstancesResult runInstancesResult  = ec2.runInstances(runInstancesRequest);
        String newInstanceId = runInstancesResult .getReservation().getInstances().get(0).getInstanceId();
        turningOnInstanceIds.add(newInstanceId);
        currentCapacity++;

        System.out.println("SCALE UP EVENT: launching instance " + newInstanceId);
    }

    private void scaleDown() {
        String instanceToRemove = getInstanceIdWithLowestLoad();
        activeInstanceIds.remove(instanceToRemove); // remove from load balancer list
        currentCapacity--;

        if (terminateInstanceIfIdle(instanceToRemove)) {
            System.out.println("SCALE DOWN EVENT: terminated instance " + instanceToRemove);
        } else {
            // mark for deletion
            waitingForDeletionInstanceIds.add(instanceToRemove);
            System.out.println("SCALE DOWN EVENT: marked instance " + instanceToRemove + " for deletion");
        }
    }

    private boolean terminateInstanceIfIdle(String instanceId) {
        if (dbWriter.isInstanceIdle(instanceId)) {
            System.out.println("AS: Terminated idle instance: " + instanceId);
            TerminateInstancesRequest terminateInstanceRequest = new TerminateInstancesRequest();
            terminateInstanceRequest.withInstanceIds(instanceId);
            ec2.terminateInstances(terminateInstanceRequest);
            waitingForDeletionInstanceIds.remove(instanceId);

            return true;
        }
        return false;
    }

    /* Manages active instances. This function is called every AutoScaler cycle, and by the Load Balancer when requests fail */
    public void checkInstanceActivity(String instanceId) {
         try {
            DescribeInstancesRequest request = new DescribeInstancesRequest().withInstanceIds(instanceId);
            DescribeInstancesResult response = ec2.describeInstances(request);

            List<Reservation> reservations = response.getReservations();
            if (reservations.isEmpty() || reservations.get(0).getInstances().isEmpty()) {
                System.out.println("AS: Instance not found: " + instanceId);
                return;
            }

            String publicIp = reservations.get(0).getInstances().get(0).getPublicIpAddress();

            if (publicIp == null || publicIp.isBlank()) {
                if (activeInstanceIds.remove(instanceId)) {
                    currentCapacity--;
                    System.out.println("AS: Instance " + instanceId + " is no longer active and accepting connections on port 8000.");
                    return;
                } else {
                    System.out.println("AS: Instance " + instanceId + " is still not accepting connections on port 8000.");
                    return;
                }
            }

            // Check if port 8000 is open
            try (Socket socket = new Socket()) {
                SocketAddress address = new InetSocketAddress(publicIp, 8000);
                socket.connect(address, 2000); // timeout: 2 seconds

                if (turningOnInstanceIds.contains(instanceId)) {
                    System.out.println("AS: Instance " + instanceId + " is running and accepting requests. Added to activeInstanceIds.");
                }

                turningOnInstanceIds.remove(instanceId);
                activeInstanceIds.add(instanceId);

            } catch (IOException e) {
                if (activeInstanceIds.remove(instanceId)) {
                    currentCapacity--;
                    System.out.println("AS: Instance " + instanceId + " is no longer active and accepting connections on port 8000.");
                } else {
                    System.out.println("AS: Instance " + instanceId + " is still not accepting connections on port 8000.");
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to check instance status: " + instanceId);
            e.printStackTrace();
        }
    }

    public Set<String> getActiveInstanceIds() {
        return Collections.unmodifiableSet(activeInstanceIds);
    }

    public String getInstanceIdWithLowestLoad() {
        String lowestLoadId = null; // fallback value
        double minCost = Double.MAX_VALUE;
        Map<String, Double> instanceLoads = new HashMap<>();

        if (activeInstanceIds.isEmpty()) {
            return null;
        }

        List<Map<String, AttributeValue>> items = dbWriter.getAllOngoingRequestMetrics();

        if (items.isEmpty()) {
            // No ongoing requests, return any instance's ID
            return activeInstanceIds.iterator().next();
        }

        for (String instanceId : activeInstanceIds) {
            instanceLoads.put(instanceId, 0.0);
        }

        for (Map<String, AttributeValue> item : items) {
            String instanceId = item.get("instanceId").getS();
            long nInst = Long.parseLong(item.get("nInstructions").getN());
            long nMem = Long.parseLong(item.get("nMemAccesses").getN());
            String handlerName = item.get("handlerName").getS();
            double cost;
            switch(handlerName) {
                case Constants.CTF_HANDLER:
                    cost = Constants.CTF_ALPHA * nInst + Constants.CTF_BETA * nMem + Constants.CTF_INTERCEPT;
                    break; 
                case Constants.GOL_HANDLER:
                    cost = Constants.GOL_ALPHA * nInst + Constants.GOL_BETA * nMem + Constants.GOL_INTERCEPT;
                    break;
                case Constants.FP_HANDLER:
                    cost = Constants.FP_ALPHA * nInst + Constants.FP_BETA * nMem + Constants.FP_BETA;
                    break;
                default:
                    // fallback to avoid nulls. Use values from regression that unifies all handlers
                    cost = ALPHA * nInst + BETA * nMem + INTERCEPT;
                    break;
            }
            instanceLoads.put(instanceId, instanceLoads.getOrDefault(instanceId, 0.0) + cost);
        }

        for (Map.Entry<String, Double> entry : instanceLoads.entrySet()) {
            if (entry.getValue() < minCost) {
                minCost = entry.getValue();
                lowestLoadId = entry.getKey();
            }
        }

        return lowestLoadId;
    }

    public void terminateAllInstances() {
        Set<String> allInstanceIds = new HashSet<>();
        allInstanceIds.addAll(activeInstanceIds);
        allInstanceIds.addAll(turningOnInstanceIds);
        allInstanceIds.addAll(waitingForDeletionInstanceIds);

        if (allInstanceIds.isEmpty()) {
            return;
        }

        try {
            TerminateInstancesRequest request = new TerminateInstancesRequest()
                .withInstanceIds(allInstanceIds);

            ec2.terminateInstances(request);

        } catch (AmazonEC2Exception e) {
            e.printStackTrace();
        }
    }
}
