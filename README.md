## Games@Cloud

This project consists of a load balancer/autoscaler instance that launches and manages webservers, instrumented with Javassist, that run computationally-intensive tasks based on games, namely: capturetheflag, fifteenpuzzle and gameoflife. To balance load efficiently, it uses two DynamoDB tables to estimate request cost and measure current load, and sends low complexity requests to Lambda.

Detailed information on this project, including reasoning for the decisions made, is available in the [report](final_project_report_CNV_g28.pdf), which explains:
- The **architecture**;
- **Instrumentation metrics**;
- **Data structures** (DynamoDB tables);
- **Request cost estimation**;
- **Task scheduling algorithm** (load balancing);
- **Auto-scaling algorithm**;
- **Fault-tolerance**.

## Modules

This project contains eight modules:

1. `capturetheflag` - the Capture the Flag workload
2. `fifteenpuzzle` - the 15-Puzzle Solver workload
3. `gameoflife` - the Conway's Game of Life workload
4. `webserver` - the web server exposing the functionality of the workloads
5. `javassist` - the Javassist agent used to extract metrics about executed requests
6. `common` - request cost estimation and request lifecycle management
7. `dynamodbclient` - manages the deployment of and interactions our DynamoDB
8. `loadbalancer` - the loadbalancer and autoscaler

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`
3. To build and deploy the entire project from scratch, including the AMI image, run the script ``general-deploy.sh``

### Project Scripts

As mentioned in the previous section, the script ``general-deploy.sh`` can be used to deploy the entire project from scratch. It does this by running the following scripts:
- The script ``create-image.sh``, which creates an AMI of the webserver.
- The script ``function-register.sh``, which registers our lambda functions in AWS. 
- The script ``launch-lb.sh``, which creates and launches our own custom loadbalancer/autoscaler in an EC2 instance.

**Note**: to run these scripts, it is necessary to write your AWS credentials into the script ``config.sh``.

**Running locally**: to run our load balancer or webservers locally, it is also necessary to load your AWS credentials into the environment with ``source config.sh`` so that they can interact with AWS resources.

**Full AWS deployment**: The script ``launch-deployment-template.sh`` can also be used to launch the deployment using alarms, load balancing and auto scaling with AWS-owned resources.

**Generating load**: in the directory ``scripts/tests/``, there are multiple scripts that send requests of different types to generate load on the webservers.
