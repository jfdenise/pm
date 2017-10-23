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

package org.jboss.provisioning.config;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.spec.FeatureDependencySpec;
import org.jboss.provisioning.spec.ConfigItemContainer;
import org.jboss.provisioning.spec.ConfigItemContainerBuilder;
import org.jboss.provisioning.spec.ConfigItem;
import org.jboss.provisioning.spec.FeatureId;
import org.jboss.provisioning.spec.SpecId;
import org.jboss.provisioning.util.StringUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureConfig extends ConfigItemContainerBuilder<FeatureConfig> implements ConfigItem, ConfigItemContainer, Cloneable {

    public static FeatureConfig newConfig(FeatureId id) throws ProvisioningDescriptionException {
        final FeatureConfig config = new FeatureConfig(id.getSpec());
        for(Map.Entry<String, String> param : id.getParams().entrySet()) {
            config.setParam(param.getKey(), param.getValue());
        }
        return config;
    }

    public static FeatureConfig newConfig(String specName) throws ProvisioningDescriptionException {
        return new FeatureConfig(specName);
    }

    SpecId specId;
    Map<String, String> params = Collections.emptyMap();
    Map<FeatureId, FeatureDependencySpec> deps = Collections.emptyMap();
    String parentRef;
    String fpDep;

    public FeatureConfig(FeatureConfig copy) {
        super(copy);
        specId = copy.specId;
        fpDep = copy.fpDep;
        deps = copy.deps;
        parentRef = copy.parentRef;
        if(copy.params.size() > 1) {
            params = new HashMap<>(copy.params);
        } else {
            params = copy.params;
        }
    }

    public FeatureConfig() {
    }

    public FeatureConfig(String specName) throws ProvisioningDescriptionException {
        this.specId = SpecId.fromString(specName);
    }

    public FeatureConfig(SpecId specId) throws ProvisioningDescriptionException {
        this.specId = specId;
    }

    @Override
    public String getFpDep() {
        return fpDep;
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    public FeatureConfig setFpDep(String fpDep) {
        this.fpDep = fpDep;
        return this;
    }

    public FeatureConfig setSpecName(String specName) throws ProvisioningDescriptionException {
        this.specId = SpecId.fromString(specName);
        return this;
    }

    public SpecId getSpecId() {
        return this.specId;
    }

    public String getParentRef() {
        return this.parentRef;
    }

    public FeatureConfig setParentRef(String parentRef) {
        this.parentRef = parentRef;
        return this;
    }

    public boolean hasParams() {
        return !params.isEmpty();
    }

    public Map<String, String> getParams() {
        return params;
    }

    public String getParam(String name) {
        return params.get(name);
    }

    public FeatureConfig setParam(String name, String value) {
        putParam(name, value);
        return this;
    }

    public String putParam(String name, String value) {
        switch(params.size()) {
            case 0:
                params = Collections.singletonMap(name, value);
                return null;
            case 1:
                final String prevValue = params.get(name);
                if(prevValue != null) {
                    params = Collections.singletonMap(name, value);
                    return prevValue;
                }
                params = new HashMap<>(params);
            default:
                return params.put(name, value);
        }
    }

    public FeatureConfig addFeatureDep(FeatureId featureId) throws ProvisioningDescriptionException {
        return addFeatureDep(FeatureDependencySpec.create(featureId));
    }

    public FeatureConfig addFeatureDep(FeatureDependencySpec dep) throws ProvisioningDescriptionException {
        switch(deps.size()) {
            case 0:
                deps = Collections.singletonMap(dep.getFeatureId(), dep);
                break;
            case 1:
                if(deps.containsKey(dep.getFeatureId())) {
                    throw new ProvisioningDescriptionException("Duplicate dependency on " + dep.getFeatureId());
                }
                final Map.Entry<FeatureId, FeatureDependencySpec> first = deps.entrySet().iterator().next();
                deps = new LinkedHashMap<>(2);
                deps.put(first.getKey(), first.getValue());
            default:
                deps.put(dep.getFeatureId(), dep);
        }
        return this;
    }

    public boolean hasFeatureDeps() {
        return !deps.isEmpty();
    }

    public Collection<FeatureDependencySpec> getFeatureDeps() {
        return deps.values();
    }

    public Set<FeatureId> getFeatureDepIds() {
        return deps.keySet();
    }

    @Override
    public boolean hasItems() {
        return !items.isEmpty();
    }

    @Override
    public List<ConfigItem> getItems() {
        return items;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((deps == null) ? 0 : deps.hashCode());
        result = prime * result + ((fpDep == null) ? 0 : fpDep.hashCode());
        result = prime * result + ((params == null) ? 0 : params.hashCode());
        result = prime * result + ((parentRef == null) ? 0 : parentRef.hashCode());
        result = prime * result + ((specId == null) ? 0 : specId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        FeatureConfig other = (FeatureConfig) obj;
        if (deps == null) {
            if (other.deps != null)
                return false;
        } else if (!deps.equals(other.deps))
            return false;
        if (fpDep == null) {
            if (other.fpDep != null)
                return false;
        } else if (!fpDep.equals(other.fpDep))
            return false;
        if (params == null) {
            if (other.params != null)
                return false;
        } else if (!params.equals(other.params))
            return false;
        if (parentRef == null) {
            if (other.parentRef != null)
                return false;
        } else if (!parentRef.equals(other.parentRef))
            return false;
        if (specId == null) {
            if (other.specId != null)
                return false;
        } else if (!specId.equals(other.specId))
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append('[').append(specId);
        if(fpDep != null) {
            buf.append(" fp=").append(fpDep);
        }
        if (!params.isEmpty()) {
            buf.append(' ');
            StringUtils.append(buf, params.entrySet());
        }
        if(parentRef != null) {
            buf.append(" parentRef=").append(parentRef);
        }
        if(!deps.isEmpty()) {
            buf.append(" dependencies=");
            StringUtils.append(buf, deps.values());
        }
        if(!items.isEmpty()) {
            buf.append(" items=");
            StringUtils.appendList(buf, items);
        }
        return buf.append(']').toString();
    }
}
