package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.io.IOException;
import java.io.OutputStream;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.Instance;

import pt.ulisboa.tecnico.cnv.common.RequestLifecycleManager;
import pt.ulisboa.tecnico.cnv.dynamodbclient.DynamoDBWriter;

public class LBFifteenPuzzleHandler implements HttpHandler {
    
    private static LambdaInstance lambdaInstance;

    public LBFifteenPuzzleHandler() {
        super();
    }

    public LBFifteenPuzzleHandler(LambdaInstance li) throws Exception{
        lambdaInstance = li;
        lambdaInstance.registerLambdaFunction(Constants.FP_JAR_PATH, String.format(Constants.FP_PATH + "." + Constants.FP_HANDLER), Constants.FP_HANDLER);
    }

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

        String args = RequestLifecycleManager.getArgsVector(query, Constants.FP_HANDLER);
        
        DynamoDBWriter dbWriter = DynamoDBWriter.getInstance();
        long[] metrics = dbWriter.getRequestMetrics(args);

        if (metrics == null) {
            // metrics = [nInsts, nMemAccesses]
            metrics = RequestLifecycleManager.estimateCost(args, Constants.FP_HANDLER);
        }

        long nInstructions = metrics[0];
        long nMemAccesses = metrics[1];

        String response = null;
        boolean useLambda = redirectToLambda(nInstructions, nMemAccesses);
        useLambda = true;

        boolean completeRequest = false;
        int retries = 0;

        while (!completeRequest) {
            if (!useLambda || retries == Constants.RETRIES) {
                // Redirect to EC2 instance
                boolean handled = false;
                String destinationId = getInstanceIdWithLowestLoad(dbWriter);
                // TODO: handle case where getInstanceIdWithLowestLoad returns null (if no instances are running)
                String destinationIp = getEC2InstanceIP(destinationId);

                System.out.println("LB: FifteenPuzzle request was sent to EC2 instance " + destinationId);

                while (!handled) {
                    try {
                        RequestLifecycleManager.registerNewRequest(args, destinationId, requestId, nInstructions, nMemAccesses, dbWriter);
                        response = get(destinationIp, Constants.FP_PATH, query);
                        if (response != null && !response.isEmpty()) handled = true;
                        else throw new IOException();
                    } catch (Exception e) {
                        RequestLifecycleManager.deregisterRequest(destinationId, requestId, dbWriter);
                        destinationId = getInstanceIdWithLowestLoad(dbWriter);
                        destinationIp = getEC2InstanceIP(destinationId);
                    }
                }

                RequestLifecycleManager.deregisterRequest(destinationId, requestId, dbWriter);
                completeRequest = true;
            } else {
                System.out.println("Try " + retries + ": FifteenPuzzle request was sent to Lambda");
                Map<String, String> req = queryToMap(query);
                String json = "{\"shuffles\":\"" + req.get("shuffles") +"\", \"size\":\"" + req.get("size") + "\"}";
                try {
                    response = lambdaInstance.lambdaRequest(json, Constants.FP_HANDLER);
                    if (response != null) {
                        completeRequest = true;
                    } else {
                        retries += 1;
                    }
                } catch (Exception e) {
                    retries += 1;
                }     
                /*
                String logResult = res.getLogResult();
                String decodedLogs = new String(Base64.getDecoder().decode(logResult), StandardCharsets.UTF_8);
                Pattern pattern = Pattern.compile("Billed Duration:\\s*(\\d+)\\s*ms");
                Matcher matcher = pattern.matcher(decodedLogs);
                if (matcher.find()) 
                    System.out.println("[" + req.get("shuffles") + "," + req.get("size") + "]" + "--" + matcher.group(1) + "ms");
                */
            }
        }
        he.sendResponseHeaders(200, response.length());
        OutputStream os = he.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private String get(String ip, String path, String query) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://" + ip + ":" + Constants.WORKER_PORT + "/" + path + "?" + query))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /* Returns the IP of the EC2 instance with the lowest load */
    private String getInstanceIdWithLowestLoad(DynamoDBWriter dbWriter) {
        return AutoScaler.getInstance().getInstanceIdWithLowestLoad();
    }

    private String getEC2InstanceIP(String instanceId) {
        if (instanceId == "local-instance") return "localhost";

        AmazonEC2 ec2 = AmazonEC2ClientBuilder.standard().withRegion(Constants.AWS_REGION).withCredentials(new EnvironmentVariableCredentialsProvider()).build();

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
        double execTime = Constants.ALPHA * nInstructions + Constants.BETA * nMemAccesses + Constants.INTERCEPT;
        return execTime < Constants.THRESHOLD;
    }

}