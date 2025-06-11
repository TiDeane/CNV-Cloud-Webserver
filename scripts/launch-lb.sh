#!/bin/bash

source config.sh

# Run new instance.
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2 \
	--instance-type t2.micro \
	--key-name $AWS_KEYPAIR_NAME \
	--security-group-ids $AWS_SECURITY_GROUP \
	--monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > loadbalancer.id
echo "New load balancer with id $(cat loadbalancer.id)."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids $(cat loadbalancer.id)
echo "New load balancer with id $(cat loadbalancer.id) is now running."

# Extract DNS name.
aws ec2 describe-instances \
	--instance-ids $(cat loadbalancer.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > loadbalancer.dns
echo "New load balancer with id $(cat loadbalancer.id) has address $(cat loadbalancer.dns)."

# Wait for instance to have SSH ready.
while ! nc -z $(cat loadbalancer.dns) 22; do
	echo "Waiting for $(cat loadbalancer.dns):22 (SSH)..."
	sleep 0.5
done
echo "New load balancer with id $(cat loadbalancer.id) is ready for SSH access."

sleep 1

cd ..
mvn clean package
cd scripts/

echo "Installing dependencies on Load Balancer"

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat loadbalancer.dns) $cmd

# Copy loadbalancer JAR
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../loadbalancer/target/loadbalancer-1.0-jar-with-dependencies.jar \
    ec2-user@$(cat loadbalancer.dns):loadbalancer.jar

# Copy personal config.sh
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/config.sh \
    ec2-user@$(cat loadbalancer.dns):config.sh

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
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat loadbalancer.dns) "$cmd"


# Setup load balancer to start on instance launch.
cmd="echo \"#!/bin/bash
cd /home/ec2-user
source config.sh
java -cp /home/ec2-user/loadbalancer.jar pt.ulisboa.tecnico.cnv.loadbalancer.LBWebServer & exit 0\" | sudo tee /etc/rc.local && sudo chmod +x /etc/rc.local"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat loadbalancer.dns) "$cmd"



# Requesting an instance reboot.
aws ec2 reboot-instances --instance-ids $(cat loadbalancer.id)
echo "Rebooting instance to test load balancer auto-start."
