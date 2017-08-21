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

package org.jboss.provisioning.feature;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.provisioning.ProvisioningDescriptionException;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureGroupConfig {

    public static class Builder {

        private final String featureGroupName;
        private boolean inheritFeatures = true;
        private Set<SpecId> includedSpecs = Collections.emptySet();
        private Map<FeatureId, FeatureConfig> includedFeatures = Collections.emptyMap();
        private Set<SpecId> excludedSpecs = Collections.emptySet();
        private Set<FeatureId> excludedFeatures = Collections.emptySet();

        private Builder(String featureGroupName, boolean inheritFeatures) {
            this.featureGroupName = featureGroupName;
            this.inheritFeatures = inheritFeatures;
        }

        public Builder inheritFeatures(boolean inheritFeatures) {
            this.inheritFeatures = inheritFeatures;
            return this;
        }

        public Builder includeSpec(String spec) throws ProvisioningDescriptionException {
            return includeSpec(SpecId.fromString(spec));
        }

        public Builder includeSpec(SpecId spec) throws ProvisioningDescriptionException {
            if(excludedSpecs.contains(spec)) {
                throw new ProvisioningDescriptionException(spec + " spec has been explicitly excluded");
            }
            switch(includedSpecs.size()) {
                case 0:
                    includedSpecs = Collections.singleton(spec);
                    break;
                case 1:
                    includedSpecs = new LinkedHashSet<>(includedSpecs);
                default:
                    includedSpecs.add(spec);
            }
            return this;
        }

        public Builder includeFeature(FeatureId featureId) throws ProvisioningDescriptionException {
            return includeFeature(featureId, null);
        }

        public Builder includeFeature(FeatureId featureId, FeatureConfig feature) throws ProvisioningDescriptionException {
            if(excludedFeatures.contains(featureId)) {
                throw new ProvisioningDescriptionException(featureId + " has been explicitly excluded");
            }
            if(feature == null) {
                feature = new FeatureConfig(featureId.specId);
            }
            for (Map.Entry<String, String> idEntry : featureId.params.entrySet()) {
                final String prevValue = feature.putParam(idEntry.getKey(), idEntry.getValue());
                if (prevValue != null && !prevValue.equals(idEntry.getValue())) {
                    throw new ProvisioningDescriptionException("Parameter " + idEntry.getKey() + " has value '"
                            + idEntry.getValue() + "' in feature ID and value '" + prevValue + "' in the feature body");
                }
            }
            switch(includedFeatures.size()) {
                case 0:
                    includedFeatures = Collections.singletonMap(featureId, feature);
                    break;
                case 1:
                    final Map.Entry<FeatureId, FeatureConfig> entry = includedFeatures.entrySet().iterator().next();
                    includedFeatures = new LinkedHashMap<>(2);
                    includedFeatures.put(entry.getKey(), entry.getValue());
                default:
                    includedFeatures.put(featureId, feature);
            }
            return this;
        }

        public Builder excludeSpec(String spec) throws ProvisioningDescriptionException {
            return excludeSpec(SpecId.fromString(spec));
        }

        public Builder excludeSpec(SpecId spec) throws ProvisioningDescriptionException {
            if(includedSpecs.contains(spec)) {
                throw new ProvisioningDescriptionException(spec + " spec has been inplicitly excluded");
            }
            switch(excludedSpecs.size()) {
                case 0:
                    excludedSpecs = Collections.singleton(spec);
                    break;
                case 1:
                    excludedSpecs = new HashSet<>(excludedSpecs);
                default:
                    excludedSpecs.add(spec);
            }
            return this;
        }

        public Builder excludeFeature(FeatureId featureId) throws ProvisioningDescriptionException {
            if(includedFeatures.containsKey(featureId)) {
                throw new ProvisioningDescriptionException(featureId + " has been explicitly included");
            }
            switch(excludedFeatures.size()) {
                case 0:
                    excludedFeatures = Collections.singleton(featureId);
                    break;
                case 1:
                    excludedFeatures = new HashSet<>(excludedFeatures);
                default:
                    excludedFeatures.add(featureId);
            }
            return this;
        }

        public FeatureGroupConfig build() {
            return new FeatureGroupConfig(this);
        }
    }

    public static Builder builder(String featureGroupName) {
        return builder(featureGroupName, true);
    }

    public static Builder builder(String featureGroupName, boolean inheritFeatures) {
        return new Builder(featureGroupName, inheritFeatures);
    }

    public static FeatureGroupConfig forGroup(String featureGroupName) {
        return new FeatureGroupConfig(featureGroupName);
    }

    final String featureGroupName;
    final boolean inheritFeatures;
    final Set<SpecId> includedSpecs;
    final Map<FeatureId, FeatureConfig> includedFeatures;
    final Set<SpecId> excludedSpecs;
    final Set<FeatureId> excludedFeatures;

    private FeatureGroupConfig(String name) {
        this.featureGroupName = name;
        this.inheritFeatures = true;
        this.includedSpecs = Collections.emptySet();
        this.includedFeatures = Collections.emptyMap();
        this.excludedSpecs = Collections.emptySet();
        this.excludedFeatures = Collections.emptySet();
    }

    private FeatureGroupConfig(Builder builder) {
        this.featureGroupName = builder.featureGroupName;
        this.inheritFeatures = builder.inheritFeatures;
        this.includedSpecs = builder.includedSpecs.size() > 1 ? Collections.unmodifiableSet(builder.includedSpecs) : builder.includedSpecs;
        this.excludedSpecs = builder.excludedSpecs.size() > 1 ? Collections.unmodifiableSet(builder.excludedSpecs) : builder.excludedSpecs;
        this.includedFeatures = builder.includedFeatures.size() > 1 ? Collections.unmodifiableMap(builder.includedFeatures) : builder.includedFeatures;
        this.excludedFeatures = builder.excludedFeatures.size() > 1 ? Collections.unmodifiableSet(builder.excludedFeatures) : builder.excludedFeatures;
    }

    public String getName() {
        return featureGroupName;
    }

    public boolean isInheritFeatures() {
        return inheritFeatures;
    }

    public boolean hasExcludedSpecs() {
        return !excludedSpecs.isEmpty();
    }

    public Set<SpecId> getExcludedSpecs() {
        return excludedSpecs;
    }

    public boolean hasIncludedSpecs() {
        return !includedSpecs.isEmpty();
    }

    public Set<SpecId> getIncludedSpecs() {
        return includedSpecs;
    }

    public boolean hasExcludedFeatures() {
        return !excludedFeatures.isEmpty();
    }

    public Set<FeatureId> getExcludedFeatures() {
        return excludedFeatures;
    }

    public boolean hasIncludedFeatures() {
        return !includedFeatures.isEmpty();
    }

    public Map<FeatureId, FeatureConfig> getIncludedFeatures() {
        return includedFeatures;
    }

    boolean isExcluded(SpecId spec) {
        return excludedSpecs.contains(spec);
    }

    boolean isExcluded(FeatureId featureId) {
        if (excludedFeatures.contains(featureId)) {
            return true;
        }
        if (excludedSpecs.contains(featureId.specId)) {
            return !includedFeatures.containsKey(featureId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((featureGroupName == null) ? 0 : featureGroupName.hashCode());
        result = prime * result + ((excludedFeatures == null) ? 0 : excludedFeatures.hashCode());
        result = prime * result + ((excludedSpecs == null) ? 0 : excludedSpecs.hashCode());
        result = prime * result + ((includedFeatures == null) ? 0 : includedFeatures.hashCode());
        result = prime * result + ((includedSpecs == null) ? 0 : includedSpecs.hashCode());
        result = prime * result + (inheritFeatures ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FeatureGroupConfig other = (FeatureGroupConfig) obj;
        if (featureGroupName == null) {
            if (other.featureGroupName != null)
                return false;
        } else if (!featureGroupName.equals(other.featureGroupName))
            return false;
        if (excludedFeatures == null) {
            if (other.excludedFeatures != null)
                return false;
        } else if (!excludedFeatures.equals(other.excludedFeatures))
            return false;
        if (excludedSpecs == null) {
            if (other.excludedSpecs != null)
                return false;
        } else if (!excludedSpecs.equals(other.excludedSpecs))
            return false;
        if (includedFeatures == null) {
            if (other.includedFeatures != null)
                return false;
        } else if (!includedFeatures.equals(other.includedFeatures))
            return false;
        if (includedSpecs == null) {
            if (other.includedSpecs != null)
                return false;
        } else if (!includedSpecs.equals(other.includedSpecs))
            return false;
        if (inheritFeatures != other.inheritFeatures)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append('[');
        if(featureGroupName != null) {
            buf.append(featureGroupName);
        }
        if(!inheritFeatures) {
            buf.append(" inherit-features=false");
        }
        if(!includedSpecs.isEmpty()) {
            buf.append(" includedSpecs=");
            final Iterator<SpecId> i = includedSpecs.iterator();
            buf.append(i.next());
            while(i.hasNext()) {
                buf.append(',').append(i.next());
            }
        }
        if(!excludedSpecs.isEmpty()) {
            buf.append(" exlcudedSpecs=");
            final Iterator<SpecId> i = excludedSpecs.iterator();
            buf.append(i.next());
            while(i.hasNext()) {
                buf.append(',').append(i.next());
            }
        }
        if(!includedFeatures.isEmpty()) {
            buf.append(" includedFeatures=[");
            final Iterator<Map.Entry<FeatureId, FeatureConfig>> i = includedFeatures.entrySet().iterator();
            Map.Entry<FeatureId, FeatureConfig> entry = i.next();
            buf.append(entry.getKey());
            if(entry.getValue() != null) {
                buf.append("->").append(entry.getValue());
            }
            while(i.hasNext()) {
                entry = i.next();
                buf.append(';').append(entry.getKey());
                if(entry.getValue() != null) {
                    buf.append("->").append(entry.getValue());
                }
            }
            buf.append(']');
        }
        if(!excludedFeatures.isEmpty()) {
            buf.append(" exlcudedFeatures=");
            final Iterator<FeatureId> i = excludedFeatures.iterator();
            buf.append(i.next());
            while(i.hasNext()) {
                buf.append(',').append(i.next());
            }
        }
        return buf.append(']').toString();
    }
}
