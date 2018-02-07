/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.provisioning.plugin.wildfly;

import static org.jboss.provisioning.plugin.wildfly.config.WildFlyPackageTasksParser.NAMESPACE_2_0;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import org.jboss.provisioning.ArtifactCoords.Gav;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.ConfigId;
import org.jboss.provisioning.config.ConfigModel;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.diff.FileSystemDiffResult;
import org.jboss.provisioning.repomanager.FeaturePackBuilder;
import org.jboss.provisioning.repomanager.PackageBuilder;
import org.jboss.provisioning.runtime.ProvisioningRuntime;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class WfDiffResult extends FileSystemDiffResult {
    private final List<Path> scripts = new ArrayList<>();
    private final List<ConfigModel> configs = new ArrayList<>();
    private final Map<Gav, ConfigId> includedConfigs = new HashMap<>();

    public WfDiffResult(Map<Gav, ConfigId> includedConfigs, List<ConfigModel> configs, List<Path> scripts, Set<Path> deletedFiles, Set<Path> addedFiles, Set<Path> modifiedBinaryFiles, Map<Path, List<String>> unifiedDiffs) {
        super(deletedFiles, addedFiles, modifiedBinaryFiles, unifiedDiffs);
        if(scripts != null) {
            this.scripts.addAll(scripts);
        }
        if (includedConfigs != null) {
            this.includedConfigs.putAll(includedConfigs);
        }
        if (configs != null) {
            this.configs.addAll(configs);
        }
    }

    public WfDiffResult(Map<Gav, ConfigId> includedConfigs, List<ConfigModel> configs, List<Path> scripts, FileSystemDiffResult result) {
        this(includedConfigs, configs, scripts, result.getDeletedFiles(), result.getAddedFiles(), result.getModifiedBinaryFiles(), result.getUnifiedDiffs());
    }

    public List<Path> getScripts() {
        return Collections.unmodifiableList(scripts);
    }

    public Map<Gav, ConfigId> getIncludedConfigs() {
        return Collections.unmodifiableMap(includedConfigs);
    }

    public List<ConfigModel> getConfigs() {
        return Collections.unmodifiableList(configs);
    }

    @Override
    public FileSystemDiffResult merge(FileSystemDiffResult result) {
        super.merge(result);
        if (result instanceof WfDiffResult) {
            this.scripts.addAll(((WfDiffResult) result).getScripts());
            this.includedConfigs.putAll(((WfDiffResult) result).getIncludedConfigs());
            this.configs.addAll(((WfDiffResult) result).getConfigs());
        }
        return this;
    }

    @Override
    public void toFeaturePack(FeaturePackBuilder fpBuilder, Map<String, FeaturePackConfig.Builder> builders, ProvisioningRuntime runtime, Path installationHome) throws ProvisioningException {
        super.toFeaturePack(fpBuilder, builders, runtime, installationHome);
        PackageBuilder updatedFiles = fpBuilder.newPackage("wildfly").setDefault();
        try {
//            for (Path src : getScripts()) {
//                try {
//                    String script = EmbeddedServer.startEmbeddedServerCommand("standalone.xml") + System.lineSeparator()
//                            + IoUtils.readFile(src).trim() + System.lineSeparator()
//                            + "exit" + System.lineSeparator();
//                    fpBuilder.writeResources(WfConstants.WILDFLY + '/' + WfConstants.SCRIPTS + '/' + src.getFileName().toString(), script);
//                } catch (IOException ex) {
//                    throw new ProvisioningException(ex);
//                }
//            }
            for (ConfigModel config : getConfigs()) {
                fpBuilder.addConfig(config);
            }
            for (Entry<Gav, ConfigId> entry : getIncludedConfigs().entrySet()) {
                String key = getDefaultName(entry.getKey());
                if (!builders.containsKey(key)) {
                    builders.put(key, FeaturePackConfig.builder(entry.getKey()));
                }
                builders.get(key).includeDefaultConfig(entry.getValue());
            }
        } catch (Exception ex) {
            runtime.getMessageWriter().error(ex, ex.getMessage());
            throw new ProvisioningException(ex);
        }
        updatedFiles.writeContent("pm/wildfly/tasks.xml", toTasks(), false);
    }

    private String getDefaultName(Gav gav) {
        StringJoiner buf = new StringJoiner(":");
        if (gav.getGroupId() != null) {
            buf.add(gav.getGroupId());
        }
        if (gav.getArtifactId() != null) {
            buf.add(gav.getArtifactId());
        }
        return buf.toString();
    }

    private String toTasks() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" ?>");
        buffer.append(System.lineSeparator());
        buffer.append(System.lineSeparator());
        buffer.append(String.format("<tasks xmlns=\"%s\">", NAMESPACE_2_0));
        buffer.append(System.lineSeparator());
        buffer.append("    <delete-paths>");
        buffer.append(System.lineSeparator());
        for (Path deleted : getDeletedFiles()) {
            buffer.append(String.format("        <delete path=\"%s\" recursive=\"%s\" />", deleted.toString(), false));
        }
        buffer.append("    </delete-paths>");
        buffer.append(System.lineSeparator());
        buffer.append("</tasks>");
        return buffer.toString();
    }
}
