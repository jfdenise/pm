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

package org.jboss.provisioning.plugin.wildfly;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.MessageWriter;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.feature.FeatureAnnotation;
import org.jboss.provisioning.plugin.ProvisionedConfigHandler;
import org.jboss.provisioning.runtime.ResolvedFeatureSpec;
import org.jboss.provisioning.state.ProvisionedConfig;
import org.jboss.provisioning.state.ProvisionedFeature;

/**
 *
 * @author Alexey Loubyansky
 */
class WfProvisionedConfigHandler implements ProvisionedConfigHandler {

    private interface NameFilter {
        boolean accepts(String name);
    }

    private class ManagedOp {
        String line;
        String addrPref;
        String name;
        List<String> addrParams = Collections.emptyList();
        List<String> opParams = Collections.emptyList();
        boolean writeAttr;

        void reset() {
            line = null;
            addrPref = null;
            name = null;
            addrParams = Collections.emptyList();
            opParams = Collections.emptyList();
            writeAttr = false;
        }

        void toCommandLine(ProvisionedFeature feature) throws ProvisioningDescriptionException {
            final String line;
            if (this.line != null) {
                line = this.line;
            } else {
                final StringBuilder buf = new StringBuilder();
                if (addrPref != null) {
                    buf.append(addrPref);
                }
                int i = 0;
                while(i < addrParams.size()) {
                    final String value = feature.getParam(addrParams.get(i++));
                    if (value == null) {
                        continue;
                    }
                    buf.append('/').append(addrParams.get(i++)).append('=').append(value);

                }
                buf.append(':').append(name);
                if(writeAttr) {
                    final String value = feature.getParam(opParams.get(0));
                    if (value == null) {
                        throw new ProvisioningDescriptionException(opParams.get(0) + " parameter is null: " + feature);
                    }
                    buf.append("(name=").append(opParams.get(1)).append(",value=").append(value).append(')');
                } else if (!opParams.isEmpty()) {
                    boolean comma = false;
                    i = 0;
                    while(i < opParams.size()) {
                        final String value = feature.getParam(opParams.get(i++));
                        if (value == null) {
                            continue;
                        }
                        if (comma) {
                            buf.append(',');
                        } else {
                            comma = true;
                            buf.append('(');
                        }
                        buf.append(opParams.get(i++)).append('=').append(value);
                    }
                    if (comma) {
                        buf.append(')');
                    }
                }
                line = buf.toString();
            }
            messageWriter.print("      " + line);
            try {
                opsWriter.write(line);
                opsWriter.newLine();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private final MessageWriter messageWriter;

    private int opsTotal;
    private ManagedOp[] ops = new ManagedOp[]{new ManagedOp()};
    private NameFilter paramFilter;

    private BufferedWriter opsWriter;

    WfProvisionedConfigHandler(MessageWriter messageWriter) {
        this.messageWriter = messageWriter;
    }

    @Override
    public void prepare(ProvisionedConfig config) throws ProvisioningException {
        final String embedCmd;
        final String logFile;
        if("standalone".equals(config.getModel())) {
            logFile = config.getProperties().get("config-name");
            if(logFile == null) {
                throw new ProvisioningException("Config " + config.getName() + " of model " + config.getModel() + " is missing property config-name");
            }
            embedCmd = "embed-server --empty-config --remove-existing --server-config=" + logFile;
            paramFilter = new NameFilter() {
                @Override
                public boolean accepts(String name) {
                    return !("profile".equals(name) || "host".equals(name));
                }
            };
        } else if("domain".equals(config.getModel())) {
            final String domainConfig = config.getProperties().get("domain-config-name");
            if(domainConfig == null) {
                throw new ProvisioningException("Config " + config.getName() + " of model " + config.getModel() + " is missing property domain-config-name");
            }
            embedCmd = "embed-host-controller --empty-host-config --remove-existing-host-config --empty-domain-config --remove-existing-domain-config --host-config=pm-tmp-host.xml --domain-config=" + domainConfig;
            logFile = domainConfig;
            paramFilter = new NameFilter() {
                @Override
                public boolean accepts(String name) {
                    return !"host".equals(name);
                }
            };
        } else if("host".equals(config.getModel())) {
            final String hostConfig = config.getProperties().get("host-config-name");
            if(hostConfig == null) {
                throw new ProvisioningException("Config " + config.getName() + " of model " + config.getModel() + " is missing property host-config-name");
            }
            final StringBuilder buf = new StringBuilder();
            buf.append("embed-host-controller --empty-host-config --remove-existing-host-config --host-config=").append(hostConfig);
            final String domainConfig = config.getProperties().get("domain-config-name");
            if(domainConfig == null) {
                buf.append(" --empty-domain-config --remove-existing-domain-config --domain-config=pm-tmp-domain.xml");
            } else {
                buf.append(" --domain-config=").append(domainConfig);
            }
            embedCmd = buf.toString();
            logFile = hostConfig;
            paramFilter = new NameFilter() {
                @Override
                public boolean accepts(String name) {
                    return !"profile".equals(name);
                }
            };
        } else {
            throw new ProvisioningException("Unsupported config model " + config.getModel());
        }

        try {
            final Path path = Paths.get("/home/olubyans/pm-scripts/" + logFile);
            messageWriter.print("Logging ops to " + path.toAbsolutePath());
            opsWriter = Files.newBufferedWriter(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            opsWriter.write(embedCmd);
            opsWriter.newLine();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void nextFeaturePack(ArtifactCoords.Gav fpGav) throws ProvisioningException {
        messageWriter.print("  " + fpGav);
    }

    @Override
    public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
        messageWriter.print("    SPEC " + spec.getName());
        if(!spec.hasAnnotations()) {
            opsTotal = 0;
            return;
        }

        final List<FeatureAnnotation> annotations = spec.getAnnotations();
        opsTotal = annotations.size();
        if(annotations.size() > 1) {
            if(ops.length < opsTotal) {
                final ManagedOp[] tmp = ops;
                ops = new ManagedOp[opsTotal];
                System.arraycopy(tmp, 0, ops, 0, tmp.length);
                for(int i = tmp.length; i < ops.length; ++i) {
                    ops[i] = new ManagedOp();
                }
            }
        }

        int i = 0;
        while (i < opsTotal) {
            final FeatureAnnotation annotation = annotations.get(i);
            messageWriter.print("      Annotation: " + annotation);
            final ManagedOp mop = ops[i++];
            mop.reset();
            mop.line = annotation.getElem(WfConstants.LINE);
            if(mop.line != null) {
                continue;
            }
            mop.name = annotation.getName();
            mop.writeAttr = mop.name.equals(WfConstants.WRITE_ATTRIBUTE);
            mop.addrPref = annotation.getElem(WfConstants.ADDR_PREF);

            String elemValue = annotation.getElem(WfConstants.SKIP_IF_FILTERED);
            final Set<String> skipIfFiltered;
            if (elemValue != null) {
                skipIfFiltered = parseSet(elemValue);
            } else {
                skipIfFiltered = Collections.emptySet();
            }

            elemValue = annotation.getElem(WfConstants.ADDR_PARAMS);
            if (elemValue == null) {
                throw new ProvisioningException("Required element " + WfConstants.ADDR_PARAMS + " is missing for " + spec.getId());
            }

            try {
                mop.addrParams = parseList(elemValue, paramFilter, skipIfFiltered);
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw an empty parameter name in annotation " + WfConstants.ADDR_PARAMS + "="
                        + elemValue + " of " + spec.getId());
            }
            if(mop.addrParams == null) {
                // skip
                mop.reset();
                --opsTotal;
                --i;
                continue;
            }

            elemValue = annotation.getElem(WfConstants.OP_PARAMS, WfConstants.PM_UNDEFINED);
            if (elemValue == null) {
                mop.opParams = Collections.emptyList();
            } else if (WfConstants.PM_UNDEFINED.equals(elemValue)) {
                if (spec.hasParams()) {
                    final Set<String> allParams = spec.getParamNames();
                    final int opParams = allParams.size() - mop.addrParams.size() / 2;
                    if(opParams == 0) {
                        mop.opParams = Collections.emptyList();
                    } else {
                        mop.opParams = new ArrayList<>(opParams*2);
                        for (String paramName : allParams) {
                            if (!mop.addrParams.contains(paramName)) {
                                if(paramFilter.accepts(paramName)) {
                                    mop.opParams.add(paramName);
                                    mop.opParams.add(paramName);
                                } else if(skipIfFiltered.contains(paramName)) {
                                    // skip
                                    mop.reset();
                                    --opsTotal;
                                    --i;
                                    continue;
                                }
                            }
                        }
                    }
                } else {
                    mop.opParams = Collections.emptyList();
                }
            } else {
                try {
                    mop.opParams = parseList(elemValue, paramFilter, skipIfFiltered);
                } catch (ProvisioningDescriptionException e) {
                    throw new ProvisioningDescriptionException("Saw empty parameter name in note " + WfConstants.ADDR_PARAMS
                            + "=" + elemValue + " of " + spec.getId());
                }
                if(mop.addrParams == null) {
                    // skip
                    mop.reset();
                    --opsTotal;
                    --i;
                    continue;
                }
            }

            elemValue = annotation.getElem(WfConstants.OP_PARAMS_MAPPING);
            if(elemValue != null) {
                mapParams(mop.opParams, elemValue, paramFilter);
            }

            if(mop.writeAttr && mop.opParams.size() != 2) {
                throw new ProvisioningDescriptionException(WfConstants.OP_PARAMS + " element of "
                        + WfConstants.WRITE_ATTRIBUTE + " annotation of " + spec.getId()
                        + " accepts only one parameter: " + annotation);
            }
        }
    }

    @Override
    public void nextFeature(ProvisionedFeature feature) throws ProvisioningException {
        if (opsTotal == 0) {
            messageWriter.print("      " + feature.getParams());
            return;
        }
        for(int i = 0; i < opsTotal; ++i) {
            ops[i].toCommandLine(feature);
        }
    }

    @Override
    public void done() throws ProvisioningException {
        try {
            opsWriter.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private static Set<String> parseSet(String str) throws ProvisioningDescriptionException {
        if (str.isEmpty()) {
            return Collections.emptySet();
        }
        int comma = str.indexOf(',');
        if (comma < 1) {
            return Collections.singleton(str);
        }
        final Set<String> set = new HashSet<>();
        int start = 0;
        while (comma > 0) {
            final String paramName = str.substring(start, comma);
            if (paramName.isEmpty()) {
                throw new ProvisioningDescriptionException("Saw an empty list item in note '" + str);
            }
            set.add(paramName);
            start = comma + 1;
            comma = str.indexOf(',', start);
        }
        if (start == str.length()) {
            throw new ProvisioningDescriptionException("Saw an empty list item in note '" + str);
        }
        set.add(str.substring(start));
        return set;
    }

    private static List<String> parseList(String str, NameFilter filter, Set<String> skipIfFiltered) throws ProvisioningDescriptionException {
        if (str.isEmpty()) {
            return Collections.emptyList();
        }
        int comma = str.indexOf(',');
        List<String> list = new ArrayList<>();
        if (comma < 1) {
            if (filter.accepts(str)) {
                list.add(str);
                list.add(str);
            } else if(skipIfFiltered.contains(str)) {
                return null;
            }
            return list;
        }
        int start = 0;
        while (comma > 0) {
            final String paramName = str.substring(start, comma);
            if (paramName.isEmpty()) {
                throw new ProvisioningDescriptionException("Saw en empty list item in note '" + str);
            }
            if (filter.accepts(paramName)) {
                list.add(paramName);
                list.add(paramName);
            } else if(skipIfFiltered.contains(paramName)) {
                return null;
            }
            start = comma + 1;
            comma = str.indexOf(',', start);
        }
        if (start == str.length()) {
            throw new ProvisioningDescriptionException("Saw an empty list item in note '" + str);
        }
        final String paramName = str.substring(start);
        if(filter.accepts(paramName)) {
            list.add(paramName);
            list.add(paramName);
        } else if(skipIfFiltered.contains(paramName)) {
            return null;
        }
        return list;
    }

    private static void mapParams(List<String> params, String str, NameFilter filter) throws ProvisioningDescriptionException {
        if (str.isEmpty()) {
            return;
        }
        int comma = str.indexOf(',');
        if (comma < 1) {
            if(filter.accepts(params.get(0))) {
                params.set(1, str);
            }
            return;
        }
        int start = 0;
        int i = 1;
        while (comma > 0) {
            final String paramName = str.substring(start, comma);
            if (paramName.isEmpty()) {
                throw new ProvisioningDescriptionException("Saw an empty list item in note '" + str);
            }
            if (filter.accepts(params.get(i - 1))) {
                params.set(i, paramName);
                i += 2;
            }
            start = comma + 1;
            comma = str.indexOf(',', start);
        }
        if (start == str.length()) {
            throw new ProvisioningDescriptionException("Saw an empty list item in note '" + str);
        }
        if(filter.accepts(params.get(i - 1))) {
            params.set(i, str.substring(start));
        }
    }
}
