#!/bin/bash

source config.sh

$DIR/create-image.sh

sleep 5

$DIR/lambda-register.sh

sleep 5

$DIR/launch-lb.sh
