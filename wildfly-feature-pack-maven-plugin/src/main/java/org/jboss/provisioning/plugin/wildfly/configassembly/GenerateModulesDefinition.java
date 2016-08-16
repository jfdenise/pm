/*
 * Copyright 2014 Red Hat, Inc.
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
package org.jboss.provisioning.plugin.wildfly.configassembly;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.provisioning.plugin.wildfly.ModuleIdentifier;
import org.jboss.provisioning.plugin.wildfly.configassembly.ModuleParser.ModuleDependency;
import org.jboss.provisioning.xml.util.AttributeValue;
import org.jboss.provisioning.xml.util.ElementNode;
import org.jboss.provisioning.xml.util.FormattingXMLStreamWriter;

/**
 * Generate module directory pattern file as used by FileSet.includes
 * file http://ant.apache.org/manual/Types/fileset.html
 *
 * @author Thomas.Diesler@jboss.com
 * @since 06-Sep-2012
 */
public class GenerateModulesDefinition {


    static final String SPLIT_PATTERN = ",(\\s*)";
    static final String SKIP_SUBSYSTEMS = "skip-subsystems";
    static final String NO_MODULE_DEPENENCIES = "no-module-dependencies";

    private final File inputFile;
    private final String profile;
    private final File resourcesDir;
    private final File modulesDir;
    private final String[] staticModules;
    private final File outputFile;

    /**
     * arg[0] - subsystems definition file as generated by {@link GenerateSubsystemsDefinition}
     * arg[1] - subsystem profile (e.g. default)
     * arg[2] - resources directory for server definitions (e.g. wildfly/build/src/main/resources)
     * arg[3] - modules directory for server definitions (e.g. wildfly/build/target/wildfly-8.0.0/modules)
     * arg[4] - a comma separated list of modules to include (e.g. org.jboss.as.standalone,org.jboss.as.console)
     * arg[5] - the output file (e.g. target/module-dependencies.txt)
     */
    public static void main(String[] args) throws Exception {
        if (args == null)
            throw new IllegalArgumentException("Null args");
        if (args.length < 6)
            throw new IllegalArgumentException("Invalid args: " + Arrays.asList(args));

        int index = 0;
        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No input file");
        }
        File inputFile = new File(args[index++]);

        if (args[index] == null) {
            throw new IllegalArgumentException("No profile");
        }
        String profile = args[index++];

        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No resources dir");
        }
        File resourcesDir = new File(args[index++]);

        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No modules dir");
        }
        File modulesDir = new File(args[index++]);

        String[] staticModules = new String[] {};
        if (args[index] != null && !args[index].isEmpty()) {
            staticModules = args[index].trim().split(SPLIT_PATTERN);
        }
        index++;

        if (args[index] == null || args[index].isEmpty()) {
            throw new IllegalArgumentException("No output file");
        }
        File outputFile = new File(args[index]);

        new GenerateModulesDefinition(inputFile, profile, resourcesDir, modulesDir, staticModules, outputFile).process();
    }

    private GenerateModulesDefinition(File inputFile, String profile, File resourcesDir, File modulesDir, String[] staticModules, File outputFile) {
        this.inputFile = inputFile;
        this.profile = profile;
        this.resourcesDir = resourcesDir;
        this.modulesDir = modulesDir;
        this.staticModules = staticModules;
        this.outputFile = outputFile;
    }

    private void process() throws XMLStreamException, IOException {

        ElementNode modulesNode = new ElementNode(null, "modules", "urn:modules:1.0");
        List<ModuleIdentifier> dependencies = new ArrayList<ModuleIdentifier>();

        if (!inputFile.getName().equals(SKIP_SUBSYSTEMS)) {
            Map<String, Map<String, SubsystemConfig>> subsystems = new HashMap<>();
            SubsystemsParser.parse(new FileInputStreamSource(inputFile), subsystems);

            for (SubsystemConfig config : subsystems.get(profile).values()) {
                File configFile = new File(resourcesDir + File.separator + config.getSubsystem());
                SubsystemParser configParser = new SubsystemParser(null, config.getSupplement(), new FileInputStreamSource(configFile));
                configParser.parse();

                ModuleIdentifier moduleId = new ModuleIdentifier(configParser.getExtensionModule());
                processModuleDependency(dependencies, modulesNode, new ModuleDependency(moduleId, false));
            }
        }

        for (String staticId : staticModules) {
            if (!staticId.isEmpty()) {
                ModuleIdentifier moduleId = ModuleIdentifier.fromString(staticId);
                processModuleDependency(dependencies, modulesNode, new ModuleDependency(moduleId, false));
            }
        }

        // sort the dependencies
        Comparator<ModuleIdentifier> comp = new Comparator<ModuleIdentifier>() {
            @Override
            public int compare(ModuleIdentifier o1, ModuleIdentifier o2) {
                return o1.toString().compareTo(o2.toString());
            }
        };
        Collections.sort(dependencies, comp);

        PrintWriter pw = new PrintWriter(new FileWriter(outputFile));
        try {
            if (!dependencies.isEmpty()) {
                for (ModuleIdentifier moduleId : dependencies) {
                    String path = moduleId.getName().replace('.', '/') + '/' + moduleId.getSlot();
                    pw.println(path + '/' + "**");
                }
            } else {
                pw.println(NO_MODULE_DEPENENCIES);
            }
        } finally {
            pw.close();
        }

        String xmloutput = outputFile.getPath();
        Writer writer = new FileWriter(xmloutput.substring(0, xmloutput.lastIndexOf(".")) + ".xml");
        try {
            XMLOutputFactory factory = XMLOutputFactory.newInstance();
            XMLStreamWriter xmlwriter = new FormattingXMLStreamWriter(factory.createXMLStreamWriter(writer));
            modulesNode.marshall(xmlwriter);
        } finally {
            writer.close();
        }
    }

    private void processModuleDependency(List<ModuleIdentifier> dependencies, ElementNode parentNode, ModuleDependency dep) throws IOException, XMLStreamException {
        ModuleIdentifier moduleId = dep.getModuleId();
        if (dependencies.contains(moduleId)) {
            return;
        }

        ElementNode moduleNode = new ElementNode(parentNode, "module");
        moduleNode.addAttribute("name", new AttributeValue(moduleId.toString()));
        parentNode.addChild(moduleNode);

        if (!dep.isOptional()) {
            dependencies.add(moduleId);

            String path = moduleId.getName().replace('.', '/') + '/' + moduleId.getSlot();
            String modulespath = "system" + File.separator + "layers" + File.separator + "base";
            File moduleFile = new File(modulesDir + File.separator + modulespath + File.separator + path + File.separator + "module.xml");

            ModuleParser moduleParser = new ModuleParser(new FileInputStreamSource(moduleFile));
            moduleParser.parse();

            List<ModuleDependency> moduledeps = moduleParser.getDependencies();
            for (ModuleDependency aux : moduledeps) {
                processModuleDependency(dependencies, moduleNode, aux);
            }
        } else {
            moduleNode.addAttribute("optional", new AttributeValue("true"));
        }
    }
}
