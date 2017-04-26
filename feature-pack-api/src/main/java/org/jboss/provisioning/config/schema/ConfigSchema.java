/*
 * Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
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

package org.jboss.provisioning.config.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.provisioning.ProvisioningDescriptionException;

/**
 *
 * @author Alexey Loubyansky
 */
public class ConfigSchema {

    public static class Builder {

        private List<XmlFeatureOccurence> rootOccurs = Collections.emptyList();
        private Map<String, XmlFeatureSpec> xmlSpecs = Collections.emptyMap();

        private int noIdCount;
        private List<FeatureConfigDescription> roots;
        private Map<String, FeatureConfigDescription> configDescr = new HashMap<>();


        private Builder() {
        }

        public Builder addFeature(XmlFeatureOccurence feature) {
            switch(rootOccurs.size()) {
                case 0:
                    rootOccurs = Collections.singletonList(feature);
                    break;
                case 1:
                    rootOccurs = new ArrayList<>(rootOccurs);
                default:
                    rootOccurs.add(feature);
            }
            return this;
        }

        public Builder addSpec(XmlFeatureSpec spec) throws ProvisioningDescriptionException {
            switch(xmlSpecs.size()) {
                case 0:
                    xmlSpecs = Collections.singletonMap(spec.getName(), spec);
                    break;
                case 1:
                    xmlSpecs = new HashMap<>(xmlSpecs);
                default:
                    xmlSpecs.put(spec.getName(), spec);
            }
            return this;
        }

        public ConfigSchema build() throws ProvisioningDescriptionException {

            if(rootOccurs.isEmpty()) {
                throw new ProvisioningDescriptionException("The schema does not include root features");
            }

            if(rootOccurs.size() == 1) {
                roots = Collections.singletonList(describeFeature(null, null, rootOccurs.iterator().next()));
            } else {
                final List<FeatureConfigDescription> tmp = new ArrayList<>(rootOccurs.size());
                for (XmlFeatureOccurence occurence : rootOccurs) {
                    tmp.add(describeFeature(null, null, occurence));
                }
                roots = Collections.unmodifiableList(tmp);
            }

            // make sure all the reference parameters are in place
            for (FeatureConfigDescription feature : configDescr.values()) {
                if (feature.path == null) {
                    continue;
                }
                if (feature.parentPath != null) {
                    for (String pathName : feature.parentPath.names) {
                        if (!feature.params.containsKey(pathName)) {
                            throw new ProvisioningDescriptionException(feature.configName + " is missing parameter " + pathName + " to set its path.");
                        }
                    }
                }
                if (!feature.configRefs.isEmpty()) {
                    for (ConfigRef ref : feature.configRefs) {
                        String[] pathParams = ref.pathParams;
                        if(pathParams == null) {
                            pathParams = configDescr.get(ref.name).path.names;
                        }
                        for (String pathName : pathParams) {
                            if (!feature.params.containsKey(pathName)) {
                                throw new ProvisioningDescriptionException(feature.configName + " is missing parameter " + pathName + " for reference " + ref);
                            }
                        }
                    }
                }
            }

            return new ConfigSchema(roots, configDescr);
        }

        private FeatureConfigDescription describeFeature(ConfigPath parentPath, String parentSpot, XmlFeatureOccurence occurence) throws ProvisioningDescriptionException {

            final XmlFeatureSpec xmlSpec = xmlSpecs.get(occurence.specName);
            if(xmlSpec == null) {
                final StringBuilder buf = new StringBuilder();
                buf.append("The schema is missing feature spec ").append(occurence.specName);
                if(occurence.name != null) {
                    buf.append(" for ").append(occurence.name);
                }
                throw new ProvisioningDescriptionException(buf.toString());
            }

            final String spot;
            final ConfigPath featurePath;
            if(occurence.name == null) {
                spot = occurence.specName + ++noIdCount;
                featurePath = null;
            } else {
                spot = occurence.name;
                featurePath = parentPath == null ? ConfigPath.create(occurence.name) : parentPath.resolve(occurence.name);
            }

            final List<FeatureConfigDescription> childDescr;
            if(xmlSpec.features.isEmpty()) {
                childDescr = Collections.emptyList();
            } else if(xmlSpec.features.size() == 1) {
                final XmlFeatureOccurence childOccurence = xmlSpec.features.values().iterator().next();
                final FeatureConfigDescription childSpec = describeFeature(featurePath, spot, childOccurence);
                childDescr = Collections.singletonList(childSpec);
            } else {
                final List<FeatureConfigDescription> tmp = new ArrayList<>(xmlSpec.features.size());
                for(XmlFeatureOccurence childOccurence : xmlSpec.features.values()) {
                    final FeatureConfigDescription childSpec = describeFeature(featurePath, spot, childOccurence);
                    tmp.add(childSpec);
                }
                childDescr = Collections.unmodifiableList(tmp);
            }

            final FeatureConfigDescription descr = new FeatureConfigDescription(spot, parentSpot, featurePath, featurePath == null ? null : parentPath, childDescr, xmlSpec, occurence);
            configDescr.put(spot, descr);
            return descr;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final List<FeatureConfigDescription> roots;
    private final Map<String, FeatureConfigDescription> configDescr;

    private ConfigSchema(List<FeatureConfigDescription> roots, Map<String, FeatureConfigDescription> featureSpecs) throws ProvisioningDescriptionException {
        this.roots = roots;
        this.configDescr = featureSpecs;
    }

    public List<FeatureConfigDescription> getRoots() {
        return roots;
    }

    public FeatureConfigDescription getDescription(String spot) throws ProvisioningDescriptionException {
        final FeatureConfigDescription descr = configDescr.get(spot);
        if(descr == null) {
            throw new ProvisioningDescriptionException("Feature description not found " + spot);
        }
        return descr;
    }
}
