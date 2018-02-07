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
package org.jboss.provisioning.wildfly.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

import nu.xom.ParsingException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Constants;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.ConfigId;
import org.jboss.provisioning.config.ConfigModel;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.layout.FeaturePackLayout;
import org.jboss.provisioning.layout.FeaturePackLayoutDescriber;
import org.jboss.provisioning.plugin.FpMavenErrors;
import org.jboss.provisioning.plugin.util.MavenPluginUtil;
import org.jboss.provisioning.plugin.wildfly.WfConstants;
import org.jboss.provisioning.spec.FeaturePackSpec;
import org.jboss.provisioning.spec.PackageSpec;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.wildfly.build.ModuleParseResult.ModuleDependency;
import org.jboss.provisioning.xml.FeaturePackXmlWriter;
import org.jboss.provisioning.xml.PackageXmlParser;
import org.jboss.provisioning.xml.PackageXmlWriter;

/**
 * This plug-in builds a WildFly feature-pack arranging the content by packages.
 * The artifact versions are resolved here. The configuration pieces are copied into
 * the feature-pack resources directory and will be assembled at the provisioning time.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "wf-build", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WfFeaturePackBuildMojo extends AbstractMojo {

    private static final ArtifactCoords WF_PLUGIN_COORDS = ArtifactCoords.newInstance("org.jboss.pm", "wildfly-provisioning-plugin", "1.0.0.Alpha-SNAPSHOT", "jar");

    private static final boolean OS_WINDOWS = PropertyUtils.isWindows();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * The configuration file used for feature pack.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * The directory the configuration file is located in.
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * A path relative to {@link #configDir} that represents the directory under which of resources such as
     * {@code configuration/standalone/subsystems.xml}, {modules}, {subsystem-templates}, etc.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;

    /**
     * The name of the server.
     */
    @Parameter(alias = "server-name", defaultValue = "${project.build.finalName}", property = "wildfly.feature.pack.serverName")
    private String serverName;

    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    /**
     * The release name
     */
    @Parameter(alias="release-name", defaultValue = "${product.release.name}", required=true)
    private String releaseName;

    @Inject
    private MavenPluginUtil mavenPluginUtil;

    private MavenProjectArtifactVersions artifactVersions;

    private WildFlyFeaturePackBuild wfFpConfig;
    private Map<String, FeaturePackLayout> fpDependencies = Collections.emptyMap();
    private final PackageSpec.Builder docsBuilder = PackageSpec.builder(WfConstants.DOCS);

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            doExecute();
        } catch(RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            throw e;
        }
    }

    private void doExecute() throws MojoExecutionException, MojoFailureException {
        artifactVersions = MavenProjectArtifactVersions.getInstance(project);

        /* normalize resourcesDir */
        if (!resourcesDir.isEmpty()) {
            switch (resourcesDir.charAt(0)) {
            case '/':
            case '\\':
                break;
            default:
                resourcesDir = "/" + resourcesDir;
                break;
            }
        }
        final Path targetResources = Paths.get(buildName, Constants.RESOURCES);
        try {
            IoUtils.copy(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources);
        } catch (IOException e1) {
            throw new MojoExecutionException(Errors.copyFile(Paths.get(configDir.getAbsolutePath()).resolve(resourcesDir), targetResources), e1);
        }

        final Path workDir = Paths.get(buildName, WfConstants.LAYOUT);
        //getLog().info("WfFeaturePackBuildMojo.execute " + workDir);
        IoUtils.recursiveDelete(workDir);
        final String fpArtifactId = project.getArtifactId() + "-new";
        final Path fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
        final Path fpPackagesDir = fpDir.resolve(Constants.PACKAGES);

        // feature-pack builder
        final FeaturePackLayout.Builder fpBuilder = FeaturePackLayout.builder(
                FeaturePackSpec.builder(ArtifactCoords.newGav(project.getGroupId(), fpArtifactId, project.getVersion())));

        // feature-pack build config
        try {
            wfFpConfig = Util.loadFeaturePackBuildConfig(getFPConfigFile());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to load feature-pack config file", e);
        }

        for(String defaultPackage : wfFpConfig.getDefaultPackages()) {
            fpBuilder.getSpecBuilder().addDefaultPackage(defaultPackage);
        }

        try {
            processFeaturePackDependencies(fpBuilder.getSpecBuilder());
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to process dependencies", e);
        }

        final Path srcModulesDir = targetResources.resolve(WfConstants.MODULES).resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS).resolve(WfConstants.BASE);
        if(!Files.exists(srcModulesDir)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(srcModulesDir));
        }

        final PackageSpec.Builder modulesAll = PackageSpec.builder(WfConstants.MODULES_ALL);
        try {
            final Map<String, Path> moduleXmlByPkgName = findModules(srcModulesDir);
            if(moduleXmlByPkgName.isEmpty()) {
                throw new MojoExecutionException("Modules not found in " + srcModulesDir);
            }
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, fpPackagesDir, modulesAll);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
        if(!fpDependencies.isEmpty()) {
            for(Map.Entry<String, FeaturePackLayout> fpDep : fpDependencies.entrySet()) {
                final FeaturePackLayout fpDepLayout = fpDep.getValue();
                if (fpDepLayout.hasPackage(WfConstants.MODULES_ALL)) {
                    modulesAll.addPackageDep(fpDep.getKey(), WfConstants.MODULES_ALL);
                }
                if(fpDepLayout.hasPackage(WfConstants.DOCS)) {
                    docsBuilder.addPackageDep(fpDep.getKey(), WfConstants.DOCS);
                }
            }
        }
        try {
            final PackageSpec modulesAllPkg = modulesAll.build();
            PackageXmlWriter.getInstance().write(modulesAllPkg, fpPackagesDir.resolve(modulesAllPkg.getName()).resolve(Constants.PACKAGE_XML));
            fpBuilder.addPackage(modulesAllPkg);
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException("Failed to add package", e);
        }

        try {
            packageContent(fpBuilder, targetResources.resolve(Constants.CONTENT), fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process content", e);
        }

        if(wfFpConfig.hasSchemaGroups()) {
            addDocsSchemas(fpPackagesDir, fpBuilder);
        }

        if(docsBuilder.hasPackageDeps()) {
            final PackageSpec docsSpec = docsBuilder.build();
            fpBuilder.addPackage(docsSpec);
            writeXml(docsSpec, fpPackagesDir.resolve(WfConstants.DOCS));
            fpBuilder.getSpecBuilder().addDefaultPackage(docsSpec.getName());
        }

        addConfigPackages(targetResources.resolve(WfConstants.CONFIG).resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);

        if(wfFpConfig.hasConfigs()) {
            for(ConfigModel config : wfFpConfig.getConfigs()) {
                try {
                    fpBuilder.getSpecBuilder().addConfig(config);
                } catch (ProvisioningDescriptionException e) {
                    throw new MojoExecutionException("Failed to add config to the feature-pack", e);
                }
            }
        }

        final FeaturePackLayout fpLayout;
        try {
            fpLayout = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpLayout.getSpec(), fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(fpDir.resolve(Constants.FEATURE_PACK_XML)), e);
        }

        copyDirIfExists(targetResources.resolve(Constants.FEATURES), fpDir.resolve(Constants.FEATURES));
        copyDirIfExists(targetResources.resolve(Constants.FEATURE_GROUPS), fpDir.resolve(Constants.FEATURE_GROUPS));
        addWildFlyPlugin(fpDir);

        // collect feature-pack resources
        final Path resourcesWildFly = fpDir.resolve(Constants.RESOURCES).resolve(WfConstants.WILDFLY);
        mkdirs(resourcesWildFly);

        // properties
        try(OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_TASKS_PROPS))) {
                getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // artifact versions
        try {
            this.artifactVersions.store(resourcesWildFly.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store artifact versions", e);
        }

        // scripts
        final Path scriptsDir = targetResources.resolve(WfConstants.SCRIPTS);
        if(Files.exists(scriptsDir)) {
            if(!Files.isDirectory(scriptsDir)) {
                throw new MojoExecutionException(WfConstants.SCRIPTS + " is not a directory");
            }
            try {
                IoUtils.copy(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS));
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(scriptsDir, resourcesWildFly.resolve(WfConstants.SCRIPTS)), e);
            }
        }

        try {
            repoSystem.install(repoSession, mavenPluginUtil.getInstallLayoutRequest(workDir, project.getFile()));
        } catch (InstallationException | IOException e) {
            throw new MojoExecutionException(FpMavenErrors.featurePackInstallation(), e);
        }
    }

    private void copyDirIfExists(final Path srcDir, final Path targetDir) throws MojoExecutionException {
        if(Files.exists(srcDir)) {
            try {
                IoUtils.copy(srcDir, targetDir);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(srcDir, targetDir), e);
            }
        }
    }

    private void addWildFlyPlugin(final Path fpDir)
            throws MojoExecutionException {
        final Path pluginsDir = fpDir.resolve(Constants.PLUGINS);
        mkdirs(pluginsDir);
        final Path wfPlugInPath;
        try {
            wfPlugInPath = resolveArtifact(WF_PLUGIN_COORDS);
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to resolve plug-in artifact " + WF_PLUGIN_COORDS);
        }
        try {
            IoUtils.copy(wfPlugInPath, pluginsDir.resolve(wfPlugInPath.getFileName()));
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.copyFile(wfPlugInPath, pluginsDir.resolve(wfPlugInPath.getFileName())));
        }
    }

    private static void mkdirs(final Path resourcesWildFly) throws MojoExecutionException {
        try {
            Files.createDirectories(resourcesWildFly);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(resourcesWildFly), e);
        }
    }

    private void addDocsSchemas(final Path fpPackagesDir, final FeaturePackLayout.Builder fpBuilder)
            throws MojoExecutionException {
        docsBuilder.addPackageDep(WfConstants.DOCS_SCHEMA, true);
        final Path schemasPackageDir = fpPackagesDir.resolve(WfConstants.DOCS_SCHEMA);
        final Path schemaGroupsTxt = schemasPackageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.SCHEMA_GROUPS_TXT);
        BufferedWriter writer = null;
        try {
            mkdirs(schemasPackageDir);
            final PackageSpec docsSchemasSpec = PackageSpec.forName(WfConstants.DOCS_SCHEMA);
            fpBuilder.addPackage(docsSchemasSpec);
            PackageXmlWriter.getInstance().write(docsSchemasSpec, schemasPackageDir.resolve(Constants.PACKAGE_XML));
            mkdirs(schemaGroupsTxt.getParent());
            writer = Files.newBufferedWriter(schemaGroupsTxt);
            for (String group : wfFpConfig.getSchemaGroups()) {
                writer.write(group);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(schemaGroupsTxt.getParent()), e);
        } catch (XMLStreamException e) {
            throw new MojoExecutionException(Errors.writeFile(schemaGroupsTxt), e);
        } finally {
            if(writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void addConfigPackages(final Path configDir, final Path packagesDir, final FeaturePackLayout.Builder fpBuilder) throws MojoExecutionException {
        if(!Files.exists(configDir)) {
            return;
        }
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for(Path configPackage : stream) {
                final Path packageDir = packagesDir.resolve(configPackage.getFileName());
                if (!Files.exists(packageDir)) {
                    mkdirs(packageDir);
                }
                IoUtils.copy(configPackage, packageDir);

                final Path packageXml = configPackage.resolve(Constants.PACKAGE_XML);
                if (Files.exists(packageXml)) {
                    final PackageSpec pkgSpec;
                    try (BufferedReader reader = Files.newBufferedReader(packageXml)) {
                        try {
                            pkgSpec = PackageXmlParser.getInstance().parse(reader);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException("Failed to parse " + packageXml, e);
                        }
                    }
                    IoUtils.copy(packageXml, packageDir.resolve(Constants.PACKAGE_XML));
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process config packages", e);
        }
    }

    private void processFeaturePackDependencies(final FeaturePackSpec.Builder fpBuilder) throws Exception {
        if(wfFpConfig.getDependencies().isEmpty()) {
            return;
        }

        fpDependencies = new HashMap<>(wfFpConfig.getDependencies().size());
        for (FeaturePackDependencySpec depSpec : wfFpConfig.getDependencies()) {
            final FeaturePackConfig depConfig = depSpec.getTarget();
            final String depStr = depConfig.getGav().toString();
            String gavStr = artifactVersions.getVersion(depStr);
            if (gavStr == null) {
                throw new MojoExecutionException("Failed resolve artifact version for " + depStr);
            }
            gavStr = gavStr.replace(depStr, depStr + "-new");
            final ArtifactCoords.Gav depGav = ArtifactCoords.newGav(gavStr);
            final FeaturePackConfig.Builder depBuilder = FeaturePackConfig.builder(depGav);
            depBuilder.setInheritPackages(depConfig.isInheritPackages());
            if (depConfig.hasExcludedPackages()) {
                try {
                    depBuilder.excludeAllPackages(depConfig.getExcludedPackages()).build();
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to process dependencies", e);
                }
            }
            if (depConfig.hasIncludedPackages()) {
                try {
                    depBuilder.includeAllPackages(depConfig.getIncludedPackages()).build();
                } catch (ProvisioningException e) {
                    throw new MojoExecutionException("Failed to process dependencies", e);
                }
            }
            depBuilder.setInheritConfigs(depConfig.isInheritConfigs());
            if (depConfig.hasDefinedConfigs()) {
                for (ConfigModel config : depConfig.getDefinedConfigs()) {
                    depBuilder.addConfig(config);
                }
            }
            if (depConfig.hasExcludedConfigs()) {
                for (ConfigId configId : depConfig.getExcludedConfigs()) {
                    depBuilder.excludeDefaultConfig(configId);
                }
            }
            if (depConfig.hasFullModelsExcluded()) {
                for (Map.Entry<String, Boolean> entry : depConfig.getFullModelsExcluded().entrySet()) {
                    depBuilder.excludeConfigModel(entry.getKey(), entry.getValue());
                }
            }
            if (depConfig.hasFullModelsIncluded()) {
                for (String model : depConfig.getFullModelsIncluded()) {
                    depBuilder.includeConfigModel(model);
                }
            }
            if (depConfig.hasIncludedConfigs()) {
                for (ConfigId includedConfig : depConfig.getIncludedConfigs()) {
                    depBuilder.includeDefaultConfig(includedConfig);
                }
            }
            if (depConfig.hasDefinedConfigs()) {
                for (ConfigModel config : depConfig.getDefinedConfigs()) {
                    depBuilder.addConfig(config);
                }
            }
            fpBuilder.addFeaturePackDep(depSpec.getName(), depBuilder.build());
            final Path depZip = resolveArtifact(depGav.toArtifactCoords());
            fpDependencies.put(depSpec.getName(), FeaturePackLayoutDescriber.describeFeaturePackZip(depZip));
        }
    }

    private void packageContent(FeaturePackLayout.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                if(pkgName.equals(WfConstants.DOCS)) {
                    try(DirectoryStream<Path> docsStream = Files.newDirectoryStream(p)) {
                        for(Path docPath : docsStream) {
                            final String docName = docPath.getFileName().toString();
                            final Path docDir = packagesDir.resolve(docName);
                            IoUtils.copy(docPath, docDir.resolve(Constants.CONTENT).resolve(WfConstants.DOCS).resolve(docName));
                            final PackageSpec.Builder builder = PackageSpec.builder(docName);
                            final PackageSpec docSpec = builder.build();
                            fpBuilder.addPackage(docSpec);
                            writeXml(docSpec, docDir);
                            docsBuilder.addPackageDep(docName, true);
                        }
                    }
                } else if(pkgName.equals("bin")) {
                    final PackageSpec.Builder binBuilder = PackageSpec.builder(pkgName);
                    final Path binPkgDir = packagesDir.resolve(pkgName).resolve(Constants.CONTENT).resolve(pkgName);
                    final PackageSpec.Builder standaloneBinBuilder = PackageSpec.builder("bin.standalone");
                    final Path binStandalonePkgDir = packagesDir.resolve("bin.standalone").resolve(Constants.CONTENT).resolve(pkgName);
                    final PackageSpec.Builder domainBinBuilder = PackageSpec.builder("bin.domain");
                    final Path binDomainPkgDir = packagesDir.resolve("bin.domain").resolve(Constants.CONTENT).resolve(pkgName);
                    try (DirectoryStream<Path> binStream = Files.newDirectoryStream(p)) {
                        for (Path binPath : binStream) {
                            final String fileName = binPath.getFileName().toString();
                            if(fileName.startsWith(WfConstants.STANDALONE)) {
                                IoUtils.copy(binPath, binStandalonePkgDir.resolve(fileName));
                            } else if(fileName.startsWith(WfConstants.DOMAIN)) {
                                IoUtils.copy(binPath, binDomainPkgDir.resolve(fileName));
                            } else {
                                IoUtils.copy(binPath, binPkgDir.resolve(fileName));
                            }
                        }
                    }

                    PackageSpec binSpec = binBuilder.build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(pkgName));

                    binSpec = standaloneBinBuilder.addPackageDep(pkgName).build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(binSpec.getName()));

                    binSpec = domainBinBuilder.addPackageDep(pkgName).build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(binSpec.getName()));
                } else {
                    final Path pkgDir = packagesDir.resolve(pkgName);
                    IoUtils.copy(p, pkgDir.resolve(Constants.CONTENT).resolve(pkgName));
                    final PackageSpec pkgSpec = PackageSpec.builder(pkgName).build();
                    writeXml(pkgSpec, pkgDir);
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        }
    }

    private Map<String, Path> findModules(Path modulesDir) throws IOException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if(Files.exists(moduleXml)) {
                    final String packageName = modulesDir.relativize(moduleXml.getParent()).toString().replace(File.separatorChar, '.');
                    moduleXmlByPkgName.put(packageName, moduleXml);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        return moduleXmlByPkgName;
    }

    private void packageModules(FeaturePackLayout.Builder fpBuilder,
            Path resourcesDir, Map<String, Path> moduleXmlByPkgName, Path packagesDir, PackageSpec.Builder modulesAll)
            throws IOException, MojoExecutionException {

        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            final String packageName = module.getKey();
            final Path moduleXml = module.getValue();

            final Path packageDir = packagesDir.resolve(packageName);
            final Path targetXml = packageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve(WfConstants.MODULE).resolve(resourcesDir.relativize(moduleXml));
            mkdirs(targetXml.getParent());
            IoUtils.copy(moduleXml.getParent(), targetXml.getParent());

            final PackageSpec.Builder pkgSpecBuilder = PackageSpec.builder(packageName);
            final ModuleParseResult parsedModule;
            try {
                parsedModule = ModuleXmlParser.parse(targetXml, WfConstants.UTF8);
                if (!parsedModule.dependencies.isEmpty()) {
                    for (ModuleDependency moduleDep : parsedModule.dependencies) {
                        final StringBuilder buf = new StringBuilder();
                        buf.append(moduleDep.getModuleId().getName()).append('.').append(moduleDep.getModuleId().getSlot());
                        final String depName = buf.toString();
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            pkgSpecBuilder.addPackageDep(depName, moduleDep.isOptional());
                        } else {
                            Map.Entry<String, FeaturePackLayout> depSrc = null;
                            if (!fpDependencies.isEmpty()) {
                                for (Map.Entry<String, FeaturePackLayout> depEntry : fpDependencies.entrySet()) {
                                    if (depEntry.getValue().hasPackage(depName)) {
                                        if (depSrc != null) {
                                            throw new MojoExecutionException("Package " + depName
                                                    + " found in more than one feature-pack dependency: " + depSrc.getKey()
                                                    + " and " + depEntry.getKey());
                                        }
                                        depSrc = depEntry;
                                    }
                                }
                            }
                            if(depSrc != null) {
                                pkgSpecBuilder.addPackageDep(depSrc.getKey(), depName, moduleDep.isOptional());
                            } else if(moduleDep.isOptional()){
                                //getLog().warn("UNSATISFIED EXTERNAL OPTIONAL DEPENDENCY " + packageName + " -> " + depName);
                            } else {
                                throw new MojoExecutionException("Package " + packageName + " has unsatisifed external dependency on package " + depName);
                            }
                        }
                    }
                }
            } catch (ParsingException e) {
                throw new IOException(Errors.parseXml(targetXml), e);
            }

            final PackageSpec pkgSpec = pkgSpecBuilder.build();
            try {
                PackageXmlWriter.getInstance().write(pkgSpec, packageDir.resolve(Constants.PACKAGE_XML));
            } catch (XMLStreamException e) {
                throw new IOException(Errors.writeFile(packageDir.resolve(Constants.PACKAGE_XML)), e);
            }
            modulesAll.addPackageDep(packageName, true);
            fpBuilder.addPackage(pkgSpec);

            if (!OS_WINDOWS) {
                Files.setPosixFilePermissions(targetXml, Files.getPosixFilePermissions(moduleXml));
            }
        }
    }

    private Properties getFPConfigProperties() {
        final Properties properties = new Properties();
        properties.put("project.version", project.getVersion());
        properties.put("product.release.name", releaseName);
        return properties;
    }

    private Path getFPConfigFile() throws ProvisioningException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if(!Files.exists(path)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(path));
        }
        return path;
    }

    private static void writeXml(PackageSpec pkgSpec, Path dir) throws MojoExecutionException {
        try {
            mkdirs(dir);
            PackageXmlWriter.getInstance().write(pkgSpec, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeFile(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, getArtifactRequest(coords));
        } catch (ArtifactResolutionException e) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords), e);
        }
        if(!result.isResolved()) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords));
        }
        if(result.isMissing()) {
            throw new ProvisioningException(FpMavenErrors.artifactMissing(coords));
        }
        return Paths.get(result.getArtifact().getFile().toURI());
    }

    private ArtifactRequest getArtifactRequest(ArtifactCoords coords) {
        final ArtifactRequest req = new ArtifactRequest();
        req.setArtifact(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getExtension(), coords.getVersion()));
        req.setRepositories(remoteRepos);
        return req;
    }
}
