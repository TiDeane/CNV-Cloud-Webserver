package pt.ulisboa.tecnico.cnv.autoscaler;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;


import java.util.Timer;
import java.util.TimerTask;

public class AutoScaler {

    private final String HANDLER = "CaptureTheFlagHandler";
    private final String PATH = "capturetheflag";
    private final String WORKER_PORT = "8000";
    private final String LAMBDA_NAME = "capturetheflag-lambda";

    private final String AWS_REGION = "eu-west-1";
    private static String AMI_ID = "ami-045df467fe4d245ab";
    private static String KEY_NAME = "homePair";
    private static String SEC_GROUP_ID = "sg-0c19d38b02f780dea";
    private static long WAIT_TIME = 1000 * 60 * 10;

    public void start() {
        Timer timer = new Timer();
        TimerTask repeatedTask = new TimerTask() {
            public void run() {
                System.out.println("Task executed at: " + System.currentTimeMillis());
            }
        };

        long delay = 0;
        long period = 5000; // 5 seconds
        timer.scheduleAtFixedRate(repeatedTask, delay, period);
    }

    public void launchAnInstance() {
        try {
            AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();

            DescribeAvailabilityZonesResult availabilityZonesResult = ec2.describeAvailabilityZones();
            System.out.println("You have access to " + availabilityZonesResult.getAvailabilityZones().size() + " Availability Zones.");
            System.out.println("You have " + ec2.describeInstances().getReservations().size() + " Amazon EC2 instance(s) running.");
            
            System.out.println("Starting a new instance.");
            RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
            runInstancesRequest.withImageId(AMI_ID)
                               .withInstanceType("t2.micro")
                               .withMinCount(1)
                                .withMaxCount(1)
                                .withKeyName(KEY_NAME)
                                .withSecurityGroupIds(SEC_GROUP_ID);
            RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);
            String newInstanceId = runInstancesResult.getReservation().getInstances().get(0).getInstanceId();
            System.out.println("You have " + ec2.describeInstances().getReservations().size() + " Amazon EC2 instance(s) running.");    
        } catch (AmazonServiceException ase) {
                System.out.println("Caught Exception: " + ase.getMessage());
                System.out.println("Reponse Status Code: " + ase.getStatusCode());
                System.out.println("Error Code: " + ase.getErrorCode());
                System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    public void terminateAnInstance(String instanceID) {
        System.out.println("Terminating the instance.");
        AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();
        TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
        termInstanceReq.withInstanceIds(instanceID);
        ec2.terminateInstances(termInstanceReq);    
    }
}
