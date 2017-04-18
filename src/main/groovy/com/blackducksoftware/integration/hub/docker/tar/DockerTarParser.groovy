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
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.blackducksoftware.integration.hub.docker.OperatingSystemEnum
import com.blackducksoftware.integration.hub.docker.PackageManagerEnum
import com.blackducksoftware.integration.hub.exception.HubIntegrationException

class DockerTarParser {
    private final Logger logger = LoggerFactory.getLogger(DockerTarParser.class)

    private static final String OS_EXTRACTION_PATTERN = "etc/(lsb-release|os-release)"

    private static final String EXTRACTION_PATTERN = "(var/lib/(dpkg|yum|rpm|apk){1}.*|${OS_EXTRACTION_PATTERN})"

    File workingDirectory

    TarExtractionResults parseImageTar(String operatingSystem, File dockerTar){
        File tarExtractionDirectory = new File(workingDirectory, 'tarExtraction')
        List<File> layerTars = extractLayerTars(tarExtractionDirectory,dockerTar)
        def layerFilesDir = new File(tarExtractionDirectory, 'layerFiles')
        layerTars.each { layerTar ->
            def layerName = layerTar.getName()
            if(StringUtils.compare(layerName,'layer.tar') == 0){
                layerName = layerTar.getParentFile().getName()
            }
            def layerOutputDir = new File(layerFilesDir, layerName)
            parseLayerTarAndExtract(EXTRACTION_PATTERN, layerTar, layerOutputDir)
        }
        TarExtractionResults results = new TarExtractionResults()
        if(StringUtils.isNotBlank(operatingSystem)){
            results.operatingSystemEnum = OperatingSystemEnum.determineOperatingSystem(operatingSystem)
        } else{
            logger.trace("Layer directory ${layerFilesDir.getName()}, looking for etc")
            def etcFile = findFileWithName(layerFilesDir, 'etc')
            if(etcFile == null || etcFile.listFiles().size() == 0){
                throw new HubIntegrationException("Could not determine the Operating System because we could not find the OS files.")
            }
            results.operatingSystemEnum = extractOperatingSystemFromFiles(etcFile.listFiles())
        }

        layerFilesDir.listFiles().each { layerDirectory ->
            logger.trace("Layer directory ${layerDirectory.getName()}, looking for lib")
            def libDir = findFileWithName(layerDirectory, 'lib')
            logger.trace('lib directory : '+libDir.getAbsolutePath())
            libDir.listFiles().each { packageManagerDirectory ->
                logger.trace(packageManagerDirectory.getAbsolutePath())
                TarExtractionResult result = new TarExtractionResult()
                result.layer = layerDirectory.getName()
                result.packageManager =PackageManagerEnum.getPackageManagerEnumByName(packageManagerDirectory.getName())
                result.extractedPackageManagerDirectory = packageManagerDirectory
                results.extractionResults.add(result)
            }
        }
        results
    }

    OperatingSystemEnum parseImageTarForOperatingSystemOnly(File dockerTar){
        File tarExtractionDirectory = new File(workingDirectory, 'tarExtraction')
        List<File> layerTars = extractLayerTars(tarExtractionDirectory,dockerTar)
        def layerFilesDir = new File(tarExtractionDirectory, 'layerFiles')
        layerTars.each { layerTar ->
            def layerName = layerTar.getName()
            if(StringUtils.compare(layerName, 'layer.tar') == 0){
                layerName = layerTar.getParentFile().getName()
            }
            def layerOutputDir = new File(layerFilesDir, layerName)
            parseLayerTarAndExtract(OS_EXTRACTION_PATTERN, layerTar, layerOutputDir)
        }
        logger.trace("Layer directory ${layerFilesDir.getName()}, looking for etc")
        def etcFile = findFileWithName(layerFilesDir, 'etc')
        if(etcFile == null || etcFile.listFiles().size() == 0){
            throw new HubIntegrationException("Could not determine the Operating System because we could not find the OS files.")
        }
        extractOperatingSystemFromFiles(etcFile.listFiles())
    }

    private OperatingSystemEnum extractOperatingSystemFromFiles(File[] osFiles){
        OperatingSystemEnum osEnum = null
        for(File osFile : osFiles){
            String linePrefix = null
            if(StringUtils.compare(osFile.getName(),'lsb-release') == 0){
                linePrefix = 'DISTRIB_ID='
            } else if(StringUtils.compare(osFile.getName(),'os-release') == 0){
                linePrefix = 'ID='
            }
            if(linePrefix != null){
                osFile.eachLine { line ->
                    line = line.trim()
                    if(line.startsWith(linePrefix)){
                        def (description, value) = line.split('=')
                        value = value.replaceAll('"', '')
                        osEnum = OperatingSystemEnum.determineOperatingSystem(value)
                    }
                }
            }
            if(osEnum != null){
                break
            }
        }
        osEnum
    }

    private File findFileWithName(File fileToSearch, String name){
        if(StringUtils.compare(fileToSearch.getName(), name) == 0){
            logger.trace("File Name ${name} found ${fileToSearch.getAbsolutePath()}")
            return fileToSearch
        } else if (fileToSearch.isDirectory()){
            File foundFile = null
            for(File subFile : fileToSearch.listFiles()){
                foundFile = findFileWithName(subFile, name)
                if(foundFile != null){
                    break
                }
            }
            return foundFile
        }
    }

    private List<File> extractLayerTars(File tarExtractionDirectory, File dockerTar){
        List<File> untaredFiles = new ArrayList<>()
        final File outputDir = new File(tarExtractionDirectory, dockerTar.getName())
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

    private void parseLayerTarAndExtract(String extractionPattern, File layerTar, File layerOutputDir){
        def layerInputStream = new TarArchiveInputStream(new FileInputStream(layerTar))
        try {
            def layerEntry
            while (null != (layerEntry = layerInputStream.getNextTarEntry())) {
                try{
                    if(shouldExtractEntry(extractionPattern, layerEntry.name)){
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
                }catch(Exception e){
                    logger.error(e.toString())
                }
            }
        } finally {
            IOUtils.closeQuietly(layerInputStream)
        }
    }


    boolean shouldExtractEntry(String extractionPattern, String entryName){
        entryName.matches(extractionPattern)
    }
}
