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

package org.jboss.provisioning.config;

import java.util.Collections;
import java.util.Map;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.ArtifactCoords.Ga;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.util.PmCollections;

/**
 *
 * @author Alexey Loubyansky
 */
public abstract class FeaturePackDepsConfigBuilder<B extends FeaturePackDepsConfigBuilder<B>> extends ConfigCustomizationsBuilder<B> {

    Map<ArtifactCoords.Ga, FeaturePackConfig> fpDeps = Collections.emptyMap();
    Map<String, FeaturePackConfig> fpDepsByOrigin = Collections.emptyMap();
    Map<ArtifactCoords.Ga, String> fpGaToOrigin = Collections.emptyMap();

    public B addFeaturePackDep(FeaturePackConfig dependency) throws ProvisioningDescriptionException {
        return addFeaturePackDep(null, dependency);
    }

    @SuppressWarnings("unchecked")
    public B addFeaturePackDep(String origin, FeaturePackConfig dependency) throws ProvisioningDescriptionException {
        if(fpDeps.containsKey(dependency.getGav().toGa())) {
            throw new ProvisioningDescriptionException("Feature-pack already added " + dependency.getGav().toGa());
        }
        if(origin != null) {
            if(fpDepsByOrigin.containsKey(origin)){
                throw new ProvisioningDescriptionException(Errors.duplicateDependencyName(origin));
            }
            fpDepsByOrigin = PmCollections.put(fpDepsByOrigin, origin, dependency);
            fpGaToOrigin = PmCollections.put(fpGaToOrigin, dependency.getGav().toGa(), origin);
        }
        fpDeps = PmCollections.putLinked(fpDeps, dependency.getGav().toGa(), dependency);
        return (B) this;
    }

    @SuppressWarnings("unchecked")
    public B removeFeaturePackDep(ArtifactCoords.Gav gav) throws ProvisioningException {
        final Ga ga = gav.toGa();
        final FeaturePackConfig fpDep = fpDeps.get(ga);
        if(fpDep == null) {
            throw new ProvisioningException(Errors.unknownFeaturePack(gav));
        }
        if(!fpDep.getGav().equals(gav)) {
            throw new ProvisioningException(Errors.unknownFeaturePack(gav));
        }
        if(fpDeps.size() == 1) {
            fpDeps = Collections.emptyMap();
            fpDepsByOrigin = Collections.emptyMap();
            fpGaToOrigin = Collections.emptyMap();
            return (B) this;
        }
        fpDeps.remove(ga);
        if(!fpGaToOrigin.isEmpty()) {
            final String origin = fpGaToOrigin.get(ga);
            if(origin != null) {
                if(fpDepsByOrigin.size() == 1) {
                    fpDepsByOrigin = Collections.emptyMap();
                    fpGaToOrigin = Collections.emptyMap();
                } else {
                    fpDepsByOrigin.remove(origin);
                    fpGaToOrigin.remove(ga);
                }
            }
        }
        return (B) this;
    }
}
