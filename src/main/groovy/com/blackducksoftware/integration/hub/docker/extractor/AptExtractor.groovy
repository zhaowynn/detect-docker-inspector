/*
 * Copyright (C) 2017 Black Duck Software Inc.
 * http://www.blackducksoftware.com/
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Black Duck Software ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Black Duck Software.
 */
package com.blackducksoftware.integration.hub.docker.extractor

import javax.annotation.PostConstruct

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import com.blackducksoftware.integration.hub.bdio.simple.model.BdioComponent
import com.blackducksoftware.integration.hub.docker.OperatingSystemEnum
import com.blackducksoftware.integration.hub.docker.PackageManagerEnum
import com.blackducksoftware.integration.hub.docker.executor.AptExecutor

@Component
class AptExtractor extends Extractor {

    @Autowired
    AptExecutor executor

    @PostConstruct
    void init() {
        def forges = [
            OperatingSystemEnum.DEBIAN.forge,
            OperatingSystemEnum.UBUNTU.forge
        ]
        initValues(PackageManagerEnum.APT, executor, forges)
    }

    List<BdioComponent> extractComponents(String[] packageList) {
        def components = []
        packageList.each { packageLine ->
            if (packageLine.contains(' ')) {
                def (packageName, version) = packageLine.split(' ')
                def index = packageName.indexOf('/')
                if (index > 0) {
                    def component = packageName.substring(0, index)
                    String externalId = "${component}/${version}"
                    components.addAll(createBdioComponent(component, version, externalId))
                }
            }
        }
        components
    }
}
