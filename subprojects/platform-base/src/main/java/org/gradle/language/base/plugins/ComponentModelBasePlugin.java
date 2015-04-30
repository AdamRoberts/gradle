/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.language.base.plugins;

import org.gradle.api.*;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.internal.BiActions;
import org.gradle.internal.TriAction;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.language.base.FunctionalSourceSet;
import org.gradle.language.base.LanguageSourceSet;
import org.gradle.language.base.ProjectSourceSet;
import org.gradle.language.base.internal.DefaultComponentSpecContainer;
import org.gradle.language.base.internal.LanguageSourceSetInternal;
import org.gradle.language.base.internal.SourceTransformTaskConfig;
import org.gradle.language.base.internal.model.BinarySpecFactoryRegistry;
import org.gradle.language.base.internal.model.CollectionBuilderCreators;
import org.gradle.language.base.internal.model.ComponentSpecInitializer;
import org.gradle.language.base.internal.registry.*;
import org.gradle.model.Defaults;
import org.gradle.model.Model;
import org.gradle.model.Mutate;
import org.gradle.model.RuleSource;
import org.gradle.model.collection.CollectionBuilder;
import org.gradle.model.internal.core.*;
import org.gradle.model.internal.core.rule.describe.NestedModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.SimpleModelRuleDescriptor;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.platform.base.*;
import org.gradle.platform.base.internal.*;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;

import static org.apache.commons.lang.StringUtils.capitalize;
import static org.gradle.internal.Cast.uncheckedCast;

/**
 * Base plugin for language support.
 *
 * Adds a {@link org.gradle.platform.base.ComponentSpecContainer} named {@code componentSpecs} to the project.
 *
 * For each binary instance added to the binaries container, registers a lifecycle task to create that binary.
 */
@Incubating
public class ComponentModelBasePlugin implements Plugin<ProjectInternal> {

    private final ModelRegistry modelRegistry;

    @Inject
    public ComponentModelBasePlugin(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(LanguageBasePlugin.class);

        String descriptor = ComponentModelBasePlugin.class.getName() + ".apply()";

        ModelCreator componentsCreator = CollectionBuilderCreators.specialized("components", ComponentSpec.class, ComponentSpecContainer.class, new Transformer<ComponentSpecContainer, CollectionBuilder<ComponentSpec>>() {
            @Override
            public ComponentSpecContainer transform(CollectionBuilder<ComponentSpec> componentSpecs) {
                return new DefaultComponentSpecContainer(componentSpecs);
            }
        }, descriptor, BiActions.doNothing());
        modelRegistry.create(componentsCreator);
        modelRegistry.getRoot().applyToAllLinksTransitive(ModelActionRole.Defaults,
            DirectNodeModelAction.of(
                ModelReference.of(ComponentSpec.class),
                new SimpleModelRuleDescriptor(descriptor),
                ComponentSpecInitializer.action()));

        modelRegistry.getRoot().applyToAllLinksTransitive(ModelActionRole.Defaults,
            DirectNodeModelAction.of(
                ModelReference.of(BinarySpec.class),
                new SimpleModelRuleDescriptor(descriptor),
                ComponentSpecInitializer.binaryAction()));

        final SimpleModelRuleDescriptor baseDescriptor = new SimpleModelRuleDescriptor("createSourceTransformTasks");
        modelRegistry.configure(ModelActionRole.Defaults, DirectNodeModelAction.of(ModelReference.of("components"), baseDescriptor, new Action<MutableModelNode>() {
            @Override
            public void execute(MutableModelNode modelNode) {
                NestedModelRuleDescriptor binariesRuleDescriptor = new NestedModelRuleDescriptor(baseDescriptor, new SimpleModelRuleDescriptor("binaries"));
                final ModelReference<LanguageTransformContainer> languageTransformContainerReference = ModelReference.of(LanguageTransformContainer.class);
                modelNode.applyToAllLinksTransitive(ModelActionRole.Finalize, TriActionBackedModelAction.of(ModelReference.of(BinarySpec.class), binariesRuleDescriptor, Collections.<ModelReference<?>>singletonList(languageTransformContainerReference), new TriAction<MutableModelNode, BinarySpec, List<ModelView<?>>>() {
                    @Override
                    public void execute(MutableModelNode modelNode, BinarySpec binarySpec, List<ModelView<?>> modelViews) {
                        final BinarySpecInternal binary = (BinarySpecInternal) binarySpec;
                        LanguageTransformContainer languageTransforms = ModelViews.getInstance(modelViews.get(0), languageTransformContainerReference);
                        for (LanguageTransform<?, ?> language : languageTransforms) {
                            if (binary.isLegacyBinary() || !language.applyToBinary(binary)) {
                                continue;
                            }

                            final SourceTransformTaskConfig taskConfig = language.getTransformTask();
                            binary.getSource().withType(language.getSourceSetType(), new Action<LanguageSourceSet>() {
                                public void execute(LanguageSourceSet languageSourceSet) {
                                    final LanguageSourceSetInternal sourceSet = (LanguageSourceSetInternal) languageSourceSet;
                                    if (sourceSet.getMayHaveSources()) {
                                        String taskName = taskConfig.getTaskPrefix() + capitalize(binary.getName()) + capitalize(sourceSet.getFullName());
                                        binary.getTasks().create(taskName, taskConfig.getTaskType(), new Action<DefaultTask>() {
                                            @Override
                                            public void execute(DefaultTask task) {
                                                taskConfig.configureTask(task, binary, sourceSet);

                                                task.dependsOn(sourceSet);
                                            }
                                        });
                                    }
                                }
                            });
                        }

                    }
                }));
            }
        }));
    }

    @SuppressWarnings("UnusedDeclaration")
    static class Rules extends RuleSource {
        @Model
        LanguageRegistry languages(ServiceRegistry serviceRegistry) {
            return serviceRegistry.get(Instantiator.class).newInstance(DefaultLanguageRegistry.class);
        }

        @Model
        LanguageTransformContainer languageTransforms(ServiceRegistry serviceRegistry) {
            return serviceRegistry.get(Instantiator.class).newInstance(DefaultLanguageTransformContainer.class);
        }

        @Defaults
        void initializeSourceSetsForComponents(ComponentSpecContainer components, LanguageRegistry languageRegistry, LanguageTransformContainer languageTransforms) {
            for (LanguageRegistration<?> languageRegistration : languageRegistry) {
                // TODO - allow beforeEach() to be applied to internal types
                components.beforeEach(ComponentSourcesRegistrationAction.create(languageRegistration, languageTransforms));
            }
        }


        @Mutate
        void closeComponentsForBinaries(CollectionBuilder<Task> tasks, ComponentSpecContainer components) {
        }

//        // Finalizing here, as we need this to run after any 'assembling' task (jar, link, etc) is created.
//        @Finalize
//        void createSourceTransformTasks(final TaskContainer tasks, final BinaryContainer binaries, LanguageTransformContainer languageTransforms) {
//            for (LanguageTransform<?, ?> language : languageTransforms) {
//                for (final BinarySpecInternal binary : binaries.withType(BinarySpecInternal.class)) {
//                    if (binary.isLegacyBinary() || !language.applyToBinary(binary)) {
//                        continue;
//                    }
//
//                    final SourceTransformTaskConfig taskConfig = language.getTransformTask();
//                    binary.getSource().withType(language.getSourceSetType(), new Action<LanguageSourceSet>() {
//                        public void execute(LanguageSourceSet languageSourceSet) {
//                            LanguageSourceSetInternal sourceSet = (LanguageSourceSetInternal) languageSourceSet;
//                            if (sourceSet.getMayHaveSources()) {
//                                String taskName = taskConfig.getTaskPrefix() + capitalize(binary.getName()) + capitalize(sourceSet.getFullName());
//                                Task task = tasks.create(taskName, taskConfig.getTaskType());
//
//                                taskConfig.configureTask(task, binary, sourceSet);
//
//                                task.dependsOn(sourceSet);
//                                binary.getTasks().add(task);
//                            }
//                        }
//                    });
//                }
//            }
//        }

        @Mutate
        void applyDefaultSourceConventions(ComponentSpecContainer componentSpecs) {
            componentSpecs.afterEach(new Action<ComponentSpec>() {
                @Override
                public void execute(ComponentSpec componentSpec) {
                    for (LanguageSourceSet languageSourceSet : componentSpec.getSource()) {
                        // Only apply default locations when none explicitly configured
                        if (languageSourceSet.getSource().getSrcDirs().isEmpty()) {
                            languageSourceSet.getSource().srcDir(String.format("src/%s/%s", componentSpec.getName(), languageSourceSet.getName()));
                        }
                    }
                }
            });
        }

        // TODO:DAZ Work out why this is required
        @Mutate
        void closeSourcesForBinaries(BinaryContainer binaries, ProjectSourceSet sources) {
            // Only required because sources aren't fully integrated into model
        }

        @Model
        PlatformContainer platforms(ServiceRegistry serviceRegistry) {
            Instantiator instantiator = serviceRegistry.get(Instantiator.class);
            return instantiator.newInstance(DefaultPlatformContainer.class, instantiator);
        }

        @Model
        PlatformResolvers platformResolver(PlatformContainer platforms, ServiceRegistry serviceRegistry) {
            Instantiator instantiator = serviceRegistry.get(Instantiator.class);
            return instantiator.newInstance(DefaultPlatformResolvers.class, platforms);
        }

        @Mutate
        void registerPlatformExtension(ExtensionContainer extensions, PlatformContainer platforms) {
            extensions.add("platforms", platforms);
        }

        @Defaults
        void registerBinaryFactories(ComponentSpecContainer componentSpecs, final BinarySpecFactoryRegistry binaryFactoryRegistry) {
            componentSpecs.beforeEach(new Action<ComponentSpec>() {
                @Override
                public void execute(ComponentSpec componentSpec) {
                    ComponentSpecInternal componentSpecInternal = uncheckedCast(componentSpec);
                    binaryFactoryRegistry.copyInto(componentSpecInternal.getBinaries());
                }
            });
        }

        @Defaults
        void collectBinaries(BinaryContainer binaries, ComponentSpecContainer componentSpecs) {
            System.out.println("collectBinaries(); = ");
            for (ComponentSpec componentSpec : componentSpecs.values()) {
                for (BinarySpec binary : componentSpec.getBinaries()) {
                    binaries.add(binary);
                }
            }
        }

    }

    // TODO:DAZ Needs to be a separate action since can't have parameterized utility methods in a RuleSource
    private static class ComponentSourcesRegistrationAction<U extends LanguageSourceSet> implements Action<ComponentSpec> {
        private final LanguageRegistration<U> languageRegistration;
        private final LanguageTransformContainer languageTransforms;

        private ComponentSourcesRegistrationAction(LanguageRegistration<U> registration, LanguageTransformContainer languageTransforms) {
            this.languageRegistration = registration;
            this.languageTransforms = languageTransforms;
        }

        public static <U extends LanguageSourceSet> ComponentSourcesRegistrationAction<U> create(LanguageRegistration<U> registration, LanguageTransformContainer languageTransforms) {
            return new ComponentSourcesRegistrationAction<U>(registration, languageTransforms);
        }

        public void execute(ComponentSpec componentSpec) {
            ComponentSpecInternal componentSpecInternal = (ComponentSpecInternal) componentSpec;
            registerLanguageSourceSetFactory(componentSpecInternal);
            createDefaultSourceSetForComponents(componentSpecInternal);
        }

        void registerLanguageSourceSetFactory(final ComponentSpecInternal component) {
            final FunctionalSourceSet functionalSourceSet = component.getSources();
            NamedDomainObjectFactory<? extends U> sourceSetFactory = languageRegistration.getSourceSetFactory(functionalSourceSet.getName());
            functionalSourceSet.registerFactory(languageRegistration.getSourceSetType(), sourceSetFactory);
        }

        // If there is a transform for the language into one of the component inputs, add a default source set
        void createDefaultSourceSetForComponents(final ComponentSpecInternal component) {
            final FunctionalSourceSet functionalSourceSet = component.getSources();
            for (LanguageTransform<?, ?> languageTransform : languageTransforms) {
                if (languageTransform.getSourceSetType().equals(languageRegistration.getSourceSetType())
                        && component.getInputTypes().contains(languageTransform.getOutputType())) {
                    functionalSourceSet.maybeCreate(languageRegistration.getName(), languageRegistration.getSourceSetType());
                    return;
                }
            }
        }
    }

}
