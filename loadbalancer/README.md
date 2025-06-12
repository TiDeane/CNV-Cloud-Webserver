## Load Balancer / Auto Scaler

This project contains the loadbalancer and autoscaler functionality.

### How to build

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`

### How to run locally

To run the loadbalancer/autoscaler locally, execute this command:

```
java -cp /home/tomas/CNVf2/cnv25-g28/loadbalancer/target/loadbalancer-1.0-jar-with-dependencies.jar pt.ulisboa.tecnico.cnv.loadbalancer.LBWebServer
```

