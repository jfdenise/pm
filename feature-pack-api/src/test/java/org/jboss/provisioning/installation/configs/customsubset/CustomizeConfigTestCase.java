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

package org.jboss.provisioning.installation.configs.customsubset;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.ArtifactCoords.Gav;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.config.ConfigModel;
import org.jboss.provisioning.config.FeatureConfig;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.config.ProvisioningConfig;
import org.jboss.provisioning.repomanager.FeaturePackRepositoryManager;
import org.jboss.provisioning.runtime.ResolvedFeatureId;
import org.jboss.provisioning.runtime.ResolvedSpecId;
import org.jboss.provisioning.spec.FeatureId;
import org.jboss.provisioning.spec.FeatureParameterSpec;
import org.jboss.provisioning.spec.FeatureSpec;
import org.jboss.provisioning.state.ProvisionedFeaturePack;
import org.jboss.provisioning.state.ProvisionedState;
import org.jboss.provisioning.test.PmProvisionConfigTestBase;
import org.jboss.provisioning.xml.ProvisionedConfigBuilder;
import org.jboss.provisioning.xml.ProvisionedFeatureBuilder;

/**
 *
 * @author Alexey Loubyansky
 */
public class CustomizeConfigTestCase extends PmProvisionConfigTestBase {

    private static final Gav FP1_GAV = ArtifactCoords.newGav("org.jboss.pm.test", "fp1", "1.0.0.Final");
    private static final Gav FP2_GAV = ArtifactCoords.newGav("org.jboss.pm.test", "fp2", "2.0.0.Final");

    @Override
    protected void setupRepo(FeaturePackRepositoryManager repoManager) throws ProvisioningDescriptionException {
        repoManager.installer()
            .newFeaturePack(FP1_GAV)
                .addSpec(FeatureSpec.builder("specA")
                        .addParam(FeatureParameterSpec.createId("id"))
                        .addParam(FeatureParameterSpec.create("p1", "feature spec"))
                        .addParam(FeatureParameterSpec.create("p2", "feature spec"))
                        .addParam(FeatureParameterSpec.create("p3", "feature spec"))
                        .build())
                .addConfig(ConfigModel.builder("model1", "config1")
                        .setProperty("prop1", "fp1")
                        .setProperty("prop2", "fp1")
                        .setProperty("prop3", "fp1")
                        .addFeature(new FeatureConfig("specA").
                                setParam("id", "1").
                                setParam("p2", "fp spec").
                                setParam("p3", "fp spec"))
                        .addFeature(new FeatureConfig("specA").
                                setParam("id", "2").
                                setParam("p2", "fp spec").
                                setParam("p3", "fp spec"))
                        .build())
                .getInstaller()
            .newFeaturePack(FP2_GAV)
                .addSpec(FeatureSpec.builder("specB")
                    .addParam(FeatureParameterSpec.createId("id"))
                        .addParam(FeatureParameterSpec.create("p1", "feature spec"))
                        .addParam(FeatureParameterSpec.create("p2", "feature spec"))
                        .addParam(FeatureParameterSpec.create("p3", "feature spec"))
                    .build())
                .addConfig(ConfigModel.builder("model1", "config1")
                        .setProperty("prop2", "fp2")
                        .setProperty("prop3", "fp2")
                        .addFeature(new FeatureConfig("specB").
                                setParam("id", "1").
                                setParam("p2", "fp spec").
                                setParam("p3", "fp spec"))
                        .addFeature(new FeatureConfig("specB").
                                setParam("id", "2").
                                setParam("p2", "fp spec").
                                setParam("p3", "fp spec"))
                        .build())
                .getInstaller()
            .install();
    }

    @Override
    protected ProvisioningConfig provisioningConfig()
            throws ProvisioningDescriptionException {
        return ProvisioningConfig.builder()
                .addFeaturePackDep("fp1", FeaturePackConfig.forGav(FP1_GAV))
                .addFeaturePackDep("fp2", FeaturePackConfig.forGav(FP2_GAV))
                .addConfig(ConfigModel.builder("model1", "config1")
                        .setProperty("prop3", "custom")
                        .includeFeature(FeatureId.create("specB", "id", "1"),
                                new FeatureConfig().setOrigin("fp2").setParam("p3", "custom"))
                        .excludeFeature("fp2", FeatureId.create("specB", "id", "2"))
                        .includeFeature(FeatureId.create("specA", "id", "2"),
                                new FeatureConfig().setOrigin("fp1").setParam("p3", "custom"))
                        .excludeFeature("fp1", FeatureId.create("specA", "id", "1"))
                        .build())
                .build();
    }

    @Override
    protected ProvisionedState provisionedState() {
        return ProvisionedState.builder()
                .addFeaturePack(ProvisionedFeaturePack.builder(FP1_GAV).build())
                .addFeaturePack(ProvisionedFeaturePack.builder(FP2_GAV).build())
                .addConfig(ProvisionedConfigBuilder.builder()
                        .setModel("model1").setName("config1")
                        .setProperty("prop1", "fp1")
                        .setProperty("prop2", "fp2")
                        .setProperty("prop3", "custom")
                        .addFeature(ProvisionedFeatureBuilder.builder(ResolvedFeatureId.create(new ResolvedSpecId(FP1_GAV,  "specA"), "id", "2"))
                                .setConfigParam("p1", "feature spec")
                                .setConfigParam("p2", "fp spec")
                                .setConfigParam("p3", "custom"))
                        .addFeature(ProvisionedFeatureBuilder.builder(ResolvedFeatureId.create(new ResolvedSpecId(FP2_GAV,  "specB"), "id", "1"))
                                .setConfigParam("p1", "feature spec")
                                .setConfigParam("p2", "fp spec")
                                .setConfigParam("p3", "custom"))
                        .build())
                .build();
    }
}
