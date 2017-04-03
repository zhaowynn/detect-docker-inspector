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
package com.blackducksoftware.integration.hub.docker.tar

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils;

import com.blackducksoftware.integration.hub.docker.OperatingSystemEnum
import com.blackducksoftware.integration.hub.docker.PackageManagerEnum
import com.blackducksoftware.integration.hub.exception.HubIntegrationException

class DockerTarParser {

    File workingDirectory

    TarExtractionResults parseImageTar(File dockerTar){
        if(workingDirectory.exists()){
            FileUtils.deleteDirectory(workingDirectory)
        }
        List<File> layerTars = extractLayerTars(dockerTar)
        def layerOutputDir = new File(workingDirectory, "layerFiles")
        layerTars.each { layerTar ->
            parseLayerTarAndExtract(layerTar, layerOutputDir)
        }
        TarExtractionResults results = new TarExtractionResults()
        results.operatingSystemEnum = extractOperatingSystemFromFile(new File(layerOutputDir, "etc").listFiles()[0])
        def packageManagerFiles =  new File(layerOutputDir, "var/lib")
        packageManagerFiles.listFiles().each { packageManagerDirectory ->
            TarExtractionResult result = new TarExtractionResult()
            result.packageManager =PackageManagerEnum.getPackageManagerEnumByName(packageManagerDirectory.getName())
            result.extractedPackageManagerDirectory = packageManagerDirectory
            results.extractionResults.add(result)
        }
        results
    }

    private OperatingSystemEnum extractOperatingSystemFromFile(File osFile){
        OperatingSystemEnum osEnum = null
        String linePrefix = null
        if(osFile.getName().equals('lsb-release')){
            linePrefix = 'DISTRIB_ID='
        } else if(osFile.getName().equals('os-release')){
            linePrefix = 'ID='
        }
        if(linePrefix != null){
            osFile.eachLine { line ->
                line = line.trim()
                if(line.startsWith(linePrefix)){
                    def (description, value) = line.split('=')
                    osEnum = OperatingSystemEnum.determineOperatingSystem(value)
                }
            }
        }
        if(osEnum == null){
            throw new HubIntegrationException('Could not determing the Operating System of this Docker tar.')
        }
        osEnum
    }

    private List<File> extractLayerTars(File dockerTar){
        List<File> untaredFiles = new ArrayList<>()
        final File outputDir = new File(workingDirectory, dockerTar.getName())
        def tarArchiveInputStream = new TarArchiveInputStream(new FileInputStream(dockerTar))
        try {
            def tarArchiveEntry
            while (null != (tarArchiveEntry = tarArchiveInputStream.getNextTarEntry())) {
                final File outputFile = new File(outputDir, tarArchiveEntry.getName())
                if (tarArchiveEntry.isDirectory()) {
                    outputFile.mkdirs()
                } else if(tarArchiveEntry.name.contains('layer.tar')){
                    final OutputStream outputFileStream = new FileOutputStream(outputFile)
                    try{
                        IOUtils.copy(tarArchiveInputStream, outputFileStream)
                        untaredFiles.add(outputFile)
                    } finally{
                        outputFileStream.close()
                    }
                }
            }
        } finally {
            IOUtils.closeQuietly(tarArchiveInputStream)
        }
        untaredFiles
    }

    private void parseLayerTarAndExtract(File layerTar, File layerOutputDir){
        def layerInputStream = new TarArchiveInputStream(new FileInputStream(layerTar))
        try {
            def layerEntry
            while (null != (layerEntry = layerInputStream.getNextTarEntry())) {
                if(shouldExtractEntry(layerEntry.name)){
                    final File outputFile = new File(layerOutputDir, layerEntry.getName())
                    if (layerEntry.isFile()) {
                        if(!outputFile.getParentFile().exists()){
                            outputFile.getParentFile().mkdirs()
                        }
                        final OutputStream outputFileStream = new FileOutputStream(outputFile)
                        try{
                            IOUtils.copy(layerInputStream, outputFileStream)
                        } finally{
                            outputFileStream.close()
                        }
                    }
                }
            }
        } finally {
            IOUtils.closeQuietly(layerInputStream)
        }
    }


    boolean shouldExtractEntry(String entryName){
        entryName.matches("(var/lib/(dpkg|apt|yum|rpm|apk){1}.*|etc/(lsb-release|os-release))")
    }
}
