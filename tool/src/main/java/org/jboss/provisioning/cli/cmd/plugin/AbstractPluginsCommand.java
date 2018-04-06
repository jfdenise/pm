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
package org.jboss.provisioning.cli.cmd.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aesh.command.Command;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.container.CommandContainer;
import org.aesh.command.impl.container.AeshCommandContainer;
import org.aesh.command.impl.internal.OptionType;
import org.aesh.command.impl.internal.ProcessedCommand;
import org.aesh.command.impl.internal.ProcessedOption;
import org.aesh.command.impl.internal.ProcessedOptionBuilder;
import org.aesh.command.impl.parser.AeshCommandLineParser;
import org.aesh.command.map.MapCommand;
import org.aesh.command.map.MapProcessedCommandBuilder;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.parser.OptionParserException;
import org.aesh.readline.AeshContext;
import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.ArtifactException;
import org.jboss.provisioning.DefaultMessageWriter;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.ProvisioningManager;
import org.jboss.provisioning.cli.CommandExecutionException;
import org.jboss.provisioning.cli.FeaturePackCommand;
import org.jboss.provisioning.cli.GavCompleter;
import org.jboss.provisioning.cli.MavenArtifactRepositoryManager;
import org.jboss.provisioning.cli.PmCommandInvocation;
import org.jboss.provisioning.cli.PmSession;
import org.jboss.provisioning.cli.StreamCompleter;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.config.ProvisioningConfig;
import org.jboss.provisioning.plugin.PluginOption;
import org.jboss.provisioning.runtime.ProvisioningRuntime;

/**
 * An abstract command that discover plugin options based on the fp or stream
 * argument.
 *
 * @author jdenise@redhat.com
 */
public abstract class AbstractPluginsCommand extends MapCommand<PmCommandInvocation> {

    // A cache of options per concrete command. The key is the concrete command name.
    private static final Map<String, Map<ArtifactCoords.Gav, List<ProcessedOption>>> CACHE = new HashMap<>();

    private class DynamicOptionsProvider implements MapProcessedCommandBuilder.ProcessedOptionProvider {

        @Override
        public List<ProcessedOption> getOptions(List<ProcessedOption> currentOptions) {
            try {
                // true is passed to not fail if the gav is invalid (partially set during completion).
                ArtifactCoords.Gav gav = getGav(pmSession, true);
                if (gav != null) {
                    // We can retrieve options
                    List<ProcessedOption> options = null;
                    Map<ArtifactCoords.Gav, List<ProcessedOption>> map = CACHE.get(getName());
                    if (map == null) {
                        map = new HashMap<>();
                        CACHE.put(getName(), map);
                    }
                    options = map.get(gav);
                    if (options == null) {
                        options = new ArrayList<>();
                        map.put(gav, options);
                        ProvisioningManager manager = getManager(ctx);
                        FeaturePackConfig config = FeaturePackConfig.forGav(gav);
                        ProvisioningConfig provisioning = ProvisioningConfig.builder().addFeaturePackDep(config).build();
                        ProvisioningRuntime runtime = manager.getRuntime(provisioning, null, Collections.emptyMap());
                        Set<PluginOption> pluginOptions = getPluginOptions(runtime);
                        for (PluginOption opt : pluginOptions) {
                            ProcessedOptionBuilder builder = ProcessedOptionBuilder.builder();
                            builder.name(opt.getName());
                            if (!opt.isAcceptsValue()) {
                                builder.type(String.class);
                                builder.optionType(OptionType.BOOLEAN);
                                builder.hasValue(false);
                                noValuesOptions.add(opt.getName());
                            } else {
                                builder.type(String.class);
                                builder.optionType(OptionType.NORMAL);
                                builder.hasValue(true);
                            }
                            builder.required(opt.isRequired());
                            options.add(builder.build());
                        }
                    }
                    return options;
                }
            } catch (Exception ex) {
                // XXX OK.
            }
            return Collections.emptyList();
        }

    }

    private static final String ARGUMENT_NAME = "org.jboss.pm.tool.stream.arg";
    private static final String VERBOSE_NAME = "verbose";
    private static final String FP_NAME = "fp";
    private final PmSession pmSession;
    private AeshContext ctx;
    private final Set<String> staticOptions = new HashSet<>();
    private ProcessedCommand<?> cmd;
    private final Set<String> noValuesOptions = new HashSet<>();
    public AbstractPluginsCommand(PmSession pmSession) {
        this.pmSession = pmSession;
    }

    public void setAeshContext(AeshContext ctx) {
        this.ctx = ctx;
    }

    public CommandContainer<Command<PmCommandInvocation>, PmCommandInvocation> createCommand() throws CommandLineParserException {
        cmd = buildCommand();
        AeshCommandContainer container = new AeshCommandContainer(
                new AeshCommandLineParser<>(cmd));
        return container;
    }

    protected boolean isVerbose() {
        return contains(VERBOSE_NAME);
    }

    private Map<String, String> getPluginOptions() throws CommandException {
        Map<String, String> options = new HashMap<>();
        for (String m : getValues().keySet()) {
            if (m == null || m.isEmpty()) {
                throw new CommandException("Invalid null option");
            }
            if (!staticOptions.contains(m)) {
                if (noValuesOptions.contains(m)) {
                    options.put(m, null);
                } else {
                    options.put(m, (String) getValue(m));
                }
            }
        }
        return options;
    }

    private ProcessedCommand buildCommand() throws CommandLineParserException {
        MapProcessedCommandBuilder builder = new MapProcessedCommandBuilder();
        builder.command(this);
        builder.name(getName());
        // These are static options
        ProcessedOption streamName = ProcessedOptionBuilder.builder().name(ARGUMENT_NAME).
                hasValue(true).
                description("stream name").
                type(String.class).
                optionType(OptionType.ARGUMENT).
                completer(StreamCompleter.class).
                activator(FeaturePackCommand.StreamNameActivator.class).
                build();
        ProcessedOption fpCoords = ProcessedOptionBuilder.builder().name(FP_NAME).
                hasValue(true).
                description("Feature-pack maven gav").
                type(String.class).
                optionType(OptionType.NORMAL).
                completer(GavCompleter.class).
                activator(FeaturePackCommand.FPActivator.class).
                build();

        ProcessedOption verbose = ProcessedOptionBuilder.builder().name(VERBOSE_NAME).
                hasValue(false).
                type(Boolean.class).
                description("Whether or not the output should be verbose").
                optionType(OptionType.BOOLEAN).
                build();

        builder.argument(streamName);
        builder.addOption(fpCoords);
        builder.addOption(verbose);
        List<ProcessedOption> otherOptions = getOtherOptions();
        builder.addOptions(otherOptions);
        staticOptions.add(streamName.name());
        staticOptions.add(fpCoords.name());
        staticOptions.add(verbose.name());
        for (ProcessedOption o : otherOptions) {
            staticOptions.add(o.name());
        }

        builder.description(getDescription());
        builder.optionProvider(new DynamicOptionsProvider());
        return builder.create();
    }

    protected List<ProcessedOption> getOtherOptions() throws OptionParserException {
        return Collections.emptyList();
    }

    @Override
    public CommandResult execute(PmCommandInvocation session) throws CommandException {
        try {
            validateOptions();
            Map<String, String> options = getPluginOptions();
            runCommand(session, options);
            return CommandResult.SUCCESS;
        } catch (Throwable t) {
            if (t instanceof RuntimeException) {
                t.printStackTrace(session.getErr());
            }

            session.print("Error: ");
            println(session, t);

            t = t.getCause();
            int offset = 1;
            while (t != null) {
                for (int i = 0; i < offset; ++i) {
                    session.print(" ");
                }
                session.print("* ");
                println(session, t);
                t = t.getCause();
                ++offset;
            }
            return CommandResult.FAILURE;
        }
    }

    private void validateOptions() throws CommandException {
        String streamName = (String) getValue(ARGUMENT_NAME);
        String fpCoords = (String) getValue(FP_NAME);
        if (streamName != null && fpCoords != null) {
            throw new CommandException("Only one of --fp or stream name must be set");
        }
        if (streamName == null && fpCoords == null) {
            throw new CommandException("--fp or stream name must be set");
        }
        // Check validity of options
        for (String o : getValues().keySet()) {
            boolean found = false;
            if (!ARGUMENT_NAME.equals(o)) {
                for (ProcessedOption opt : cmd.getOptions()) {
                    if (opt.name().equals(o)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new CommandException("Unknown option " + o);
                }
            }
        }
    }

    private void println(PmCommandInvocation session, Throwable t) {
        if (t.getLocalizedMessage() == null) {
            session.println(t.getClass().getName());
        } else {
            session.println(t.getLocalizedMessage());
        }
    }

    protected abstract void runCommand(PmCommandInvocation session, Map<String, String> options) throws CommandExecutionException;

    protected abstract Set<PluginOption> getPluginOptions(ProvisioningRuntime runtime) throws ProvisioningException;

    protected abstract String getName();

    protected abstract String getDescription();

    protected abstract Path getInstallationHome(AeshContext ctx);

    private ProvisioningManager getManager(AeshContext ctx) {
        ProvisioningManager manager = ProvisioningManager.builder()
                .setArtifactResolver(MavenArtifactRepositoryManager.getInstance())
                .setInstallationHome(getInstallationHome(ctx))
                .build();
        return manager;
    }

    protected ProvisioningManager getManager(PmCommandInvocation session) {
        return ProvisioningManager.builder()
                .setArtifactResolver(MavenArtifactRepositoryManager.getInstance())
                .setInstallationHome(getInstallationHome(session.getAeshContext()))
                .setMessageWriter(new DefaultMessageWriter(session.getOut(), session.getErr(), isVerbose()))
                .build();
    }

    protected ArtifactCoords.Gav getGav(PmSession session, boolean completion) throws CommandExecutionException {
        String streamName = (String) getValue(ARGUMENT_NAME);
        String fpCoords = (String) getValue(FP_NAME);
        if (fpCoords == null && streamName == null) {
            // Check in argument, that is the option completion case.
            String val = cmd.getArgument().getValue();
            if (val == null) {
                return null;
            } else {
                streamName = val;
            }
        }

        String coords;
        if (streamName != null) {
            try {
                coords = session.getUniverses().resolveStream(streamName).toString();
            } catch (ArtifactException ex) {
                if (!completion) {
                    throw new CommandExecutionException("Stream resolution failed", ex);
                } else {
                    return null;
                }
            }
        } else {
            coords = fpCoords;
        }
        return ArtifactCoords.newGav(coords);
    }
}
