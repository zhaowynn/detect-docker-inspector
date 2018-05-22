/**
 * hub-docker-inspector
 *
 * Copyright (C) 2018 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.blackducksoftware.integration.hub.docker.dockerinspector.restclient;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.blackducksoftware.integration.exception.IntegrationException;
import com.blackducksoftware.integration.hub.docker.dockerinspector.InspectorImages;
import com.blackducksoftware.integration.hub.docker.dockerinspector.config.Config;
import com.blackducksoftware.integration.hub.docker.dockerinspector.config.ProgramPaths;
import com.blackducksoftware.integration.hub.docker.dockerinspector.dockerclient.DockerClientManager;
import com.blackducksoftware.integration.hub.docker.dockerinspector.dockerclient.HubDockerClient;
import com.blackducksoftware.integration.hub.exception.HubIntegrationException;
import com.blackducksoftware.integration.hub.imageinspector.lib.OperatingSystemEnum;
import com.blackducksoftware.integration.rest.connection.RestConnection;
import com.blackducksoftware.integration.rest.exception.IntegrationRestException;
import com.github.dockerjava.api.model.Container;

@Component
public class ImageInspectorClientContainersStartedAsNeeded implements ImageInspectorClient {
    private static final String HUB_IMAGEINSPECTOR_WS_APPNAME = "hub-imageinspector-ws";
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final int MAX_CONTAINER_START_TRY_COUNT = 10;

    @Autowired
    private Config config;

    @Autowired
    private ImageInspectorServices imageInspectorServices;

    @Autowired
    private RestConnectionCreator restConnectionCreator;

    @Autowired
    private RestRequestor restRequestor;

    @Autowired
    private InspectorImages inspectorImages;

    @Autowired
    private DockerClientManager dockerClientManager;

    @Autowired
    private ProgramPaths programPaths;

    @Autowired
    private HubDockerClient hubDockerClient;

    @Override
    public boolean isApplicable() {
        final boolean answer = config.isImageInspectorServiceStart();
        logger.debug(String.format("isApplicable() returning %b", answer));
        return answer;
    }

    @Override
    public String getBdio(final String hostPathToTarfile, final String containerPathToTarfile, final String containerFileSystemFilename, final boolean cleanup) throws IntegrationException {
        logger.debug(String.format("getBdio(): containerPathToTarfile: %s", containerPathToTarfile));

        // First, try the default inspector service (which will return either the BDIO, or a redirect)
        final String imageInspectorUrl = deriveInspectorUrl(imageInspectorServices.getDefaultImageInspectorPort());
        final int serviceRequestTimeoutSeconds = deriveTimeoutSeconds();
        final RestConnection restConnection = createRestConnection(imageInspectorUrl, serviceRequestTimeoutSeconds);
        final String containerId = ensureServiceReady(restConnection, imageInspectorUrl);
        copyFileToContainer(hostPathToTarfile, containerId, containerPathToTarfile);
        logger.debug(String.format("Sending getBdio request to: %s", imageInspectorUrl));
        try {
            final String bdio = restRequestor.executeGetBdioRequest(restConnection, imageInspectorUrl, containerPathToTarfile, containerFileSystemFilename, cleanup);
            return bdio;
        } catch (final IntegrationRestException restException) {
            // TODO: If get a redirect, grab the OS out of the body, and fire up THAT container (if it's not running)
            logger.debug(String.format("*** IntegrationRestException thrown: %s", restException.getMessage()));
            logger.debug(String.format("HttpStatusCode: %d", restException.getHttpStatusCode()));
            logger.debug(String.format("HttpStatusMessage: %s", restException.getHttpStatusMessage()));
            throw restException;
        }

    }

    private int deriveTimeoutSeconds() {
        return (int) (config.getCommandTimeout() / 1000L);
    }

    private String deriveInspectorUrl(final int inspectorPort) {
        final String imageInspectorUrl = String.format("http://localhost:%d", inspectorPort);
        logger.info(String.format("ImageInspector URL: %s", imageInspectorUrl));
        return imageInspectorUrl;
    }

    private RestConnection createRestConnection(final String imageInspectorUrl, final int serviceRequestTimeoutSeconds) throws IntegrationException {
        logger.debug(String.format("Creating a rest connection (%d second timeout) for URL: %s", serviceRequestTimeoutSeconds, imageInspectorUrl));
        RestConnection restConnection;
        try {
            restConnection = restConnectionCreator.createNonRedirectingConnection(imageInspectorUrl, serviceRequestTimeoutSeconds);
        } catch (final MalformedURLException e) {
            throw new IntegrationException(String.format("Error creating connection for URL: %s, timeout: %d", imageInspectorUrl, serviceRequestTimeoutSeconds), e);
        }
        return restConnection;
    }

    private void copyFileToContainer(final String hostPathToTarfile, final String containerId, final String containerPathToTarfile) throws HubIntegrationException, IntegrationException {
        final File containerDockerTarfile = new File(containerPathToTarfile);
        final String containerDestDirPath = containerDockerTarfile.getParent();
        try {
            dockerClientManager.copyFileToContainer(hubDockerClient.getDockerClient(), containerId, hostPathToTarfile, containerDestDirPath);
        } catch (final IOException e) {
            throw new IntegrationException(String.format("Error copying file %s to %s:%s", hostPathToTarfile, containerId, containerDestDirPath), e);
        }
    }

    private String ensureServiceReady(final RestConnection restConnection, final String imageInspectorUrl) throws IntegrationException {
        boolean serviceIsUp = checkServiceHealth(restConnection, imageInspectorUrl);
        if (serviceIsUp) {
            final Container container = dockerClientManager.getRunningContainerByAppName(hubDockerClient.getDockerClient(), HUB_IMAGEINSPECTOR_WS_APPNAME, imageInspectorServices.getDefaultImageInspectorOs());
            return container.getId();
        }

        // Need to fire up container
        final OperatingSystemEnum inspectorOs = OperatingSystemEnum.determineOperatingSystem(config.getImageInspectorDefault());
        final String imageInspectorRepo;
        final String imageInspectorTag;
        try {
            imageInspectorRepo = inspectorImages.getInspectorImageName(inspectorOs);
            imageInspectorTag = inspectorImages.getInspectorImageTag(inspectorOs);
        } catch (final IOException e) {
            throw new IntegrationException(String.format("Error getting image inspector container repo/tag for default inspector OS: %s", inspectorOs.name()), e);
        }
        logger.debug(String.format("Need to pull/run %s:%s", imageInspectorRepo, imageInspectorTag));
        final String imageId = dockerClientManager.pullImage(imageInspectorRepo, imageInspectorTag);

        final String containerName = programPaths.deriveContainerName(imageInspectorRepo);
        final String containerId = dockerClientManager.startContainerAsService(imageId, containerName, imageInspectorServices.getDefaultImageInspectorOsName());
        // TODO will need containerId later to stop/remove it since we launched it

        for (int tryIndex = 0; tryIndex < MAX_CONTAINER_START_TRY_COUNT && !serviceIsUp; tryIndex++) {
            try {
                // TODO sleep time configurable?
                logger.debug("Pausing 10 seconds to give service time to start up");
                Thread.sleep(10000L);
            } catch (final InterruptedException e) {
                logger.error(String.format("Interrupted exception thrown while pausing so image imspector container based on image %s:%s could start", imageInspectorRepo, imageInspectorTag), e);
            }
            logger.debug(String.format("Checking service %s to see if it is up; attempt %d of %d", imageInspectorUrl, tryIndex + 1, MAX_CONTAINER_START_TRY_COUNT));
            serviceIsUp = checkServiceHealth(restConnection, imageInspectorUrl);
        }
        if (!serviceIsUp) {
            throw new IntegrationException(String.format("Tried to start image imspector container %s:%s, but service %s never came online", imageInspectorRepo, imageInspectorTag, imageInspectorUrl));
        }
        return containerId;
    }

    private boolean checkServiceHealth(final RestConnection restConnection, final String imageInspectorUrl) throws IntegrationException {
        logger.debug(String.format("Sending request for health check to: %s", imageInspectorUrl));
        String healthCheckResponse;
        try {
            healthCheckResponse = restRequestor.executeSimpleGetRequest(restConnection, imageInspectorUrl, "health");
        } catch (final IntegrationException e) {
            logger.debug(String.format("Health check failed: %s", e.getMessage()));
            return false;
        }
        logger.debug(String.format("ImageInspector health check response: %s", healthCheckResponse));
        final boolean serviceIsUp = healthCheckResponse.contains("\"status\":\"UP\"");
        return serviceIsUp;
    }
}
