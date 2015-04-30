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

package org.gradle.nativeplatform.test.plugins;

import org.gradle.api.*;
import org.gradle.internal.BiActions;
import org.gradle.language.base.internal.model.CollectionBuilderCreators;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.nativeplatform.DependentSourceSet;
import org.gradle.model.Finalize;
import org.gradle.model.Mutate;
import org.gradle.model.RuleSource;
import org.gradle.model.collection.CollectionBuilder;
import org.gradle.model.internal.core.ModelCreator;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.nativeplatform.NativeBinarySpec;
import org.gradle.nativeplatform.internal.NativeBinarySpecInternal;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.test.NativeTestSuiteBinarySpec;
import org.gradle.nativeplatform.test.NativeTestSuiteSpec;
import org.gradle.nativeplatform.test.tasks.RunTestExecutable;
import org.gradle.platform.base.BinaryContainer;
import org.gradle.platform.base.BinaryTasksCollection;
import org.gradle.platform.base.ComponentSpecContainer;
import org.gradle.platform.base.internal.BinaryNamingScheme;
import org.gradle.platform.base.internal.test.DefaultTestSuiteContainer;
import org.gradle.platform.base.test.TestSuiteContainer;
import org.gradle.platform.base.test.TestSuiteSpec;

import javax.inject.Inject;
import java.io.File;

/**
 * A plugin that sets up the infrastructure for testing native binaries with CUnit.
 */
@Incubating
public class NativeBinariesTestPlugin implements Plugin<Project> {
    private final ModelRegistry modelRegistry;

    @Inject
    public NativeBinariesTestPlugin(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    public void apply(final Project project) {
        project.getPluginManager().apply(NativeComponentPlugin.class);
        String descriptor = NativeBinariesTestPlugin.class.getName() + ".apply()";
        ModelCreator testSuitesCreator = CollectionBuilderCreators.specialized("testSuites", TestSuiteSpec.class, TestSuiteContainer.class, new Transformer<TestSuiteContainer, CollectionBuilder<TestSuiteSpec>>() {
            @Override
            public TestSuiteContainer transform(CollectionBuilder<TestSuiteSpec> testSuiteSpecs) {
                return new DefaultTestSuiteContainer(testSuiteSpecs);
            }
        }, descriptor, BiActions.doNothing());

        modelRegistry.create(testSuitesCreator);
    }

    @SuppressWarnings("UnusedDeclaration")
    static class Rules extends RuleSource {
        @Finalize
            // Must run after test binaries have been created (currently in CUnit plugin)
        void attachTestedBinarySourcesToTestBinaries(BinaryContainer binaries) {
            for (NativeTestSuiteBinarySpec testSuiteBinary : binaries.withType(NativeTestSuiteBinarySpec.class)) {
                NativeBinarySpec testedBinary = testSuiteBinary.getTestedBinary();
                testSuiteBinary.source(testedBinary.getSource());

                for (DependentSourceSet testSource : testSuiteBinary.getSource().withType(DependentSourceSet.class)) {
                    testSource.lib(testedBinary.getSource());
                }
            }
        }

        @Finalize
        public void createTestTasks(TestSuiteContainer testSuiteSpecs, ComponentSpecContainer componentSpecContainer    ) {
            testSuiteSpecs.withType(NativeTestSuiteSpec.class).afterEach(new Action<NativeTestSuiteSpec>() {
                @Override
                public void execute(NativeTestSuiteSpec nativeTestSuiteSpec) {
                    for (final NativeTestSuiteBinarySpec testBinary : nativeTestSuiteSpec.getBinaries().withType(NativeTestSuiteBinarySpec.class)) {
                        final NativeBinarySpecInternal binary = (NativeBinarySpecInternal) testBinary;
                        final BinaryNamingScheme namingScheme = binary.getNamingScheme();

                        testBinary.getTasks().create(namingScheme.getTaskName("run"), RunTestExecutable.class, new Action<RunTestExecutable>() {
                            @Override
                            public void execute(RunTestExecutable runTask) {
                                final Project project = runTask.getProject();
                                runTask.setDescription(String.format("Runs the %s", binary));

                                BinaryTasksCollection tasks = binary.getTasks();
                                final DomainObjectSet<InstallExecutable> installExecutables = testBinary.getTasks().withType(InstallExecutable.class);
                                final InstallExecutable installTask = tasks.withType(InstallExecutable.class).iterator().next();
                                runTask.getInputs().files(installTask.getOutputs().getFiles());
                                runTask.setExecutable(installTask.getRunScript().getPath());
                                runTask.setOutputDir(new File(project.getBuildDir(), "/test-results/" + namingScheme.getOutputDirectoryBase()));

                                testBinary.getTasks().add(runTask);
                            }
                        });

                    }
                }
            });

        }

        @Mutate
        void attachBinariesToCheckLifecycle(CollectionBuilder<Task> tasks, final BinaryContainer binaries) {
            // TODO - binaries aren't an input to this rule, they're an input to the action
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME, new Action<Task>() {
                @Override
                public void execute(Task checkTask) {
                    for (NativeTestSuiteBinarySpec testBinary : binaries.withType(NativeTestSuiteBinarySpec.class)) {
                        checkTask.dependsOn(testBinary.getTasks().getRun());
                    }
                }
            });
        }
    }

}
