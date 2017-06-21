#!/bin/bash

docker stop $(docker ps -a -q)
docker rm $(docker ps -a -q)

docker rmi blackducksoftware/hub-docker-inspector:0.1.0
docker rmi blackducksoftware/hub-docker-inspector-centos:0.1.0
docker rmi blackducksoftware/hub-docker-inspector-alpine:0.1.0

docker rmi blackducksoftware/hub-docker-inspector:0.0.5
docker rmi blackducksoftware/hub-docker-inspector-centos:0.0.5
docker rmi blackducksoftware/hub-docker-inspector-alpine:0.0.5

docker rmi blackducksoftware/hub-docker-inspector:0.0.5-SNAPSHOT
docker rmi blackducksoftware/hub-docker-inspector-centos:0.0.5-SNAPSHOT
docker rmi blackducksoftware/hub-docker-inspector-alpine:0.0.5-SNAPSHOT

docker images


