package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.io.IOException;
import java.io.OutputStream;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Instance;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pt.ulisboa.tecnico.cnv.common.RequestLifecycleManager;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

public class LBFifteenPuzzleHandler implements HttpHandler {

    private final String HANDLER = "FifteenPuzzleHandler";
    private final String PATH = "fifteenpuzzle";
    private final String WORKER_PORT = "8000";
    private final String LAMBDA_NAME = "fifteenpuzzle-lambda";

    private final String AWS_REGION = "eu-west-1";

    private static final double ALPHA = -1.4726764102867243e-6;
    private static final double BETA = 6.223594986449469e-5;
    private static final double INTERCEPT = 206.2278774369248;
    private static final double THRESHOLD = 4100.0;

    @Override
    public void handle(HttpExchange he) throws IOException {
        // Handling CORS
        he.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        if (he.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            he.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            he.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            he.sendResponseHeaders(204, -1);
            return;
        }

        // parse request
        URI requestedUri = he.getRequestURI();
        String query = requestedUri.getRawQuery();
        String requestId = UUID.randomUUID().toString();
        query += "&requestId=" + requestId;

        String args = RequestLifecycleManager.getArgsVector(query, HANDLER);
        
        DynamoDBWriter dbWriter = DynamoDBWriter.getInstance();
        long[] metrics = dbWriter.getRequestMetrics(args);

        if (metrics == null) {
            // metrics = [nInsts, nMemAccesses]
            metrics = RequestLifecycleManager.estimateCost(args, HANDLER);
        }

        long nInstructions = metrics[0];
        long nMemAccesses = metrics[1];
        
        // TODO: keep list of active instances, for cases where there are no ongoing instances

        String response = null;
        boolean useLambda = redirectToLambda(nInstructions, nMemAccesses);

        if (!useLambda) { // TODO: implement complexity threshold for EC2 and Lambda
            // Redirect to EC2 instance
            System.out.println("Using EC2 instance for fifteenpuzzle");
            boolean handled = false;
            String destinationId = getInstanceIdWithLowestLoad(dbWriter);
            String destinationIp = getEC2InstanceIP(destinationId);

            while (!handled) {
                try {
                    RequestLifecycleManager.registerNewRequest(args, destinationId, requestId, nInstructions, nMemAccesses, dbWriter);
                    response = get(destinationIp, PATH, query);
                    if (response != null) handled = true;
                    else throw new IOException();
                } catch (Exception e) {
                    RequestLifecycleManager.deregisterRequest(destinationId, requestId, dbWriter);
                    destinationId = getInstanceIdWithLowestLoad(dbWriter);
                    destinationIp = getEC2InstanceIP(destinationId);
                }
            }

            RequestLifecycleManager.deregisterRequest(destinationId, requestId, dbWriter);

        } else {
            Map<String, String> req = queryToMap(query);
            AWSLambda client = AWSLambdaClientBuilder.standard().withRegion(AWS_REGION).build();
            String json = "{\"shuffles\":\"" + req.get("shuffles") +"\", \"size\":\"" + req.get("size") + "\"}";
            InvokeRequest request = new InvokeRequest().withFunctionName(LAMBDA_NAME).withLogType("Tail").withPayload(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            InvokeResult res = client.invoke(request);
            response = new String(res.getPayload().array(), StandardCharsets.UTF_8);

            /*String logResult = res.getLogResult();
            String decodedLogs = new String(Base64.getDecoder().decode(logResult), StandardCharsets.UTF_8);
            Pattern pattern = Pattern.compile("Billed Duration:\\s*(\\d+)\\s*ms");
            Matcher matcher = pattern.matcher(decodedLogs);
            if (matcher.find()) 
                System.out.println("[" + req.get("shuffles") + "," + req.get("size") + "]" + "--" + matcher.group(1) + "ms");*/
        }
        
        he.sendResponseHeaders(200, response.length());
        OutputStream os = he.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private String get(String ip, String path, String query) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://" + ip + ":" + WORKER_PORT + "/" + path + "?" + query))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /* Returns the IP of the EC2 instance with the lowest load */
    private String getInstanceIdWithLowestLoad(DynamoDBWriter dbWriter) {
        String destinationId = "local-instance";
        double minCost = Double.MAX_VALUE;
        Map<String, Double> instanceLoads = new HashMap<>();

        List<Map<String, AttributeValue>> items = dbWriter.getAllOngoingRequestMetrics();

        for (Map<String, AttributeValue> item : items) {
            String instanceId = item.get("instanceId").getS();
            long nInst = Long.parseLong(item.get("nInstructions").getN());
            long nMem = Long.parseLong(item.get("nMemAccesses").getN());

            // TODO: how best to aggregate cost?
            double cost = 0.1 * nInst + 0.3 * nMem;
            instanceLoads.put(instanceId, instanceLoads.getOrDefault(instanceId, 0.0) + cost);
        }

        for (Map.Entry<String, Double> entry : instanceLoads.entrySet()) {
            if (entry.getValue() < minCost) {
                minCost = entry.getValue();
                destinationId = entry.getKey();
            }
        }
        
        // TODO: if there are no ongoing requests, return a random running instance's ID

        return destinationId;
    }

    private String getEC2InstanceIP(String instanceId) {
        if (instanceId == "local-instance") return "localhost";

        AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion(AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();

        DescribeInstancesRequest request = new DescribeInstancesRequest().withInstanceIds(instanceId);

        for (Reservation reservation : ec2.describeInstances(request).getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                return instance.getPublicIpAddress();
            }
        }

        return "localhost";
    }

    private static Map<String, String> queryToMap(String query) {
        if (query == null) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }

    private boolean redirectToLambda(long nInstructions, long nMemAccesses) {
        double execTime = ALPHA * nInstructions + BETA * nMemAccesses + INTERCEPT;
        return execTime < THRESHOLD;
    }

}