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

    private static double SCALE_UP_THRESHOLD = 70.0;     // Average CPU > 70% → scale up
    private static double SCALE_DOWN_THRESHOLD = 20.0;   // Average CPU < 20% → scale down
    private static int COOLDOWN_PERIOD = 300;            // 5 minutes cooldown period
    private static long OBS_TIME = 1000 * 60 * 5;        // 5 minutes cloudwatch observation period


    private static String AWS_REGION = "eu-west-1";
    private static String AMI_ID = "ami-0dc0accdfb741c964";        // replace with webserver AMI
    private static String INSTANCE_TYPE = "t2.micro";
    private static String KEY_NAME = "eu-west-1-keypair";          // replace with your keypair
    private static String SEC_GROUP_ID = "sg-0b1ed456f41ca73b2";   // replace with your security group

    private static final AutoScaler instance = new AutoScaler();

    private AutoScaler() {
        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();
        ec2 = AmazonEC2ClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();
        this.activeInstanceIds = new HashSet<>();
        this.turningOnInstanceIds = new HashSet<>();
        this.waitingForDeletionInstanceIds = new HashSet<>();
        this.currentCapacity = 0;
        this.dbWriter = DynamoDBWriter.getInstance();
    }

    public static AutoScaler getInstance() {
        return instance;
    }

    public void evaluateAndScale() {
        for (String instanceId :  waitingForDeletionInstanceIds) {
            terminateInstanceIfIdle(instanceId);
        }

        for (String instanceId : turningOnInstanceIds) {
            addInstanceToListIfRunning(instanceId);
        }

        long now = Instant.now().getEpochSecond();
        
        if (now - lastScalingEvent < COOLDOWN_PERIOD) {
            // less than 5 minutes since last scaling event
            System.out.println("AutoScaler still cooling down...");
            return;
        }

        double averageCpu = getAverageCpuUtilization();
        System.out.printf("Average CPU Utilization: %.2f%%\n", averageCpu);

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
            System.out.println("No scaling action needed.");
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
            
            System.out.println("Received this many datapoints: " + cloudWatch.getMetricStatistics(request).getDatapoints().size());

            for (Datapoint dp : cloudWatch.getMetricStatistics(request).getDatapoints()) {
                System.out.println("CPU utilization for instance " + instanceId + " = " + dp.getAverage());
                totalCpu += dp.getAverage();
                count++;
            }
        }

        return count == 0 ? 0.0 : totalCpu / count;
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

        System.out.println("Scaled up: launching instance " + newInstanceId);
    }

    private void scaleDown() {
        String instanceToRemove = getInstanceIdWithLowestLoad();
        activeInstanceIds.remove(instanceToRemove); // remove from load balancer list
        currentCapacity--;

        if (terminateInstanceIfIdle(instanceToRemove)) {
            System.out.println("Scaled down: terminated instance " + instanceToRemove);
        } else {
            // mark for deletion
            waitingForDeletionInstanceIds.add(instanceToRemove);
            System.out.println("Scaled down: marked instance " + instanceToRemove + "for deletion");
        }
    }

    private boolean terminateInstanceIfIdle(String instanceId) {
        if (dbWriter.isInstanceIdle(instanceId)) {
            System.out.println("Terminated idle instance: " + instanceId);
            TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
            termInstanceReq.withInstanceIds(instanceId);
            ec2.terminateInstances(termInstanceReq);

            return true;
        }
        return false;
    }

    private void addInstanceToListIfRunning(String instanceId) {
        try {
            // Get instance info
            DescribeInstancesRequest request = new DescribeInstancesRequest().withInstanceIds(instanceId);
            DescribeInstancesResult response = ec2.describeInstances(request);

            List<Reservation> reservations = response.getReservations();
            if (reservations.isEmpty() || reservations.get(0).getInstances().isEmpty()) {
                System.out.println("Instance not found: " + instanceId);
                return;
            }

            String publicIp = reservations.get(0).getInstances().get(0).getPublicIpAddress();

            // Check if port 8000 is open
            try (Socket socket = new Socket()) {
                SocketAddress address = new InetSocketAddress(publicIp, 8000);
                socket.connect(address, 2000); // timeout: 2 seconds
                turningOnInstanceIds.remove(instanceId);
                activeInstanceIds.add(instanceId);

                System.out.println("Instance " + instanceId + " is running and accepting requests. Added to activeInstanceIds.");
            } catch (IOException e) {
                System.out.println("Instance " + instanceId + " is not accepting connections on port 8000.");
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
            System.out.println("No active instances, wait for the auto scaler...");
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

            double cost = 0.1 * nInst + 3.0 * nMem; // TODO: determine weights with linear regression
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
