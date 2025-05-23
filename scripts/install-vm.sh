#!/bin/bash

source config.sh

cd ../javassist/
mvn clean package # not sure if packaging javassist separately is necessary
cd ..
mvn clean package
cd scripts/

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd

# Copy webserver JAR
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    ec2-user@$(cat instance.dns):webserver.jar

# Copy Javassist agent JAR
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar \
    ec2-user@$(cat instance.dns):javassist-agent.jar

# Create metrics.txt file (for testing - later on, write to DynamoDB instead of to a file)
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) "touch metrics.txt"


# Setup rc-local.service
cmd="echo \"[Unit]
 Description=/etc/rc.local Compatibility
 ConditionPathExists=/etc/rc.local

[Service]
 Type=forking
 ExecStart=/etc/rc.local start
 TimeoutSec=0
 StandardOutput=tty
 RemainAfterExit=yes
 SysVStartPriority=99

[Install]
 WantedBy=multi-user.target\" | sudo tee /etc/systemd/system/rc-local.service"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) "$cmd"


# Setup web server to start on instance launch.
cmd="echo \"#!/bin/bash
cd /home/ec2-user
java -cp /home/ec2-user/webserver.jar \\
    -Xbootclasspath/a:/home/ec2-user/javassist-agent.jar \\
    -javaagent:/home/ec2-user/webserver.jar=MetricsExtractor:pt.ulisboa.tecnico.cnv.capturetheflag,pt.ulisboa.tecnico.cnv.fifteenpuzzle,pt.ulisboa.tecnico.cnv.gameoflife:output \\
    pt.ulisboa.tecnico.cnv.webserver.WebServer &
exit 0\" | sudo tee /etc/rc.local && sudo chmod +x /etc/rc.local"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) "$cmd"