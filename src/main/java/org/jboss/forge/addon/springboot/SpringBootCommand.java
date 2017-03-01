/**
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.springboot;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.ProjectFactory;
import org.jboss.forge.addon.projects.ui.AbstractProjectCommand;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.springboot.dto.SpringBootDependencyDTO;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UINavigationContext;
import org.jboss.forge.addon.ui.context.UIValidationContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectMany;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.NavigationResult;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Commands;
import org.jboss.forge.addon.ui.util.Metadata;

public class SpringBootCommand extends AbstractProjectCommand implements UICommand  {

   @Inject
   @WithAttributes(label = "Dependencies", required = true, description = "Add Spring Boot Starters and dependencies to your application")
   public UISelectMany<SpringBootDependencyDTO> dependencies;

   public List<SpringBootDependencyDTO> choices;

   @Inject
   @WithAttributes(label = "Spring Boot Version", required = true, description = "Spring Boot Version to use")
   public UISelectOne<String> springBootVersion;

   @Inject
   private ProjectFactory projectFactory;

   @Override
   protected boolean isProjectRequired() {
      return false;
   }

   @Override
   public ProjectFactory getProjectFactory() {
      return projectFactory;
   }

   @Inject
   private UIInput<String> lastName;

   @Override
   public void initializeUI(UIBuilder builder) throws Exception {
      lastName.setRequired(true);

      springBootVersion.setValueChoices(Arrays.asList("1.4.1"));
      springBootVersion.setDefaultValue("1.4.1");

      choices = initDependencies();

      dependencies.setValueChoices(choices);
      if (builder.getUIContext().getProvider().isGUI()) {
         dependencies.setItemLabelConverter(SpringBootDependencyDTO::getGroupAndName);
      } else {
         // if in CLI mode then use shorter names so they are tab friendly in the shell
         dependencies.setItemLabelConverter(dto -> Commands.shellifyCommandName(dto.getName()));
      }

      dependencies.setValueConverter(s -> {
         for (SpringBootDependencyDTO dto : choices) {
            if (dto.getId().equals(s)) {
               return dto;
            }
         }
         return null;
      });

      builder.getUIContext().getAttributeMap().put("springboot-version",springBootVersion.getValue());
      builder.getUIContext().getAttributeMap().put("dependencies",dependencies.getValue());

      builder.add(lastName).add(springBootVersion).add(dependencies);
   }

   @Override
   public void validate(UIValidationContext context) { }

   @Override
   public Result execute(UIExecutionContext context) throws Exception {
      UIContext uiContext = context.getUIContext();
      Project project = (Project) uiContext.getAttributeMap().get(Project.class);
      if (project == null) {
         project = getSelectedProject(context.getUIContext());
      }
      File folder = project.getRoot().reify(DirectoryResource.class).getUnderlyingResourceObject();
      System.out.println("Folder: " + folder.getAbsolutePath());
      return Results.success("Goodbye, " + lastName.getValue());
   }

   @Override
   public UICommandMetadata getMetadata(UIContext context) {
      return Metadata
              .forCommand(SpringBootCommand.class)
              .name("spring-boot-command")
              .description("generic test command")
              .category(Categories.create("Spring Boot"));
   }

   @Override
   public boolean isEnabled(UIContext context)
   {
      return true;
   }


   private List<SpringBootDependencyDTO> initDependencies() {
      List<SpringBootDependencyDTO> list = new ArrayList<>();
      SpringBootDependencyDTO dto = new SpringBootDependencyDTO("toto",
              "camel-zipkin-starter", "Apache Camel Zipkin",
              "Distributed tracing with an existing Zipkin installation with Apache Camel.");
      String version = SpringBootVersionHelper.getVersion("camel.version");
      dto.setMavenCoord("org.apache.camel", "camel-zipkin", version);
      list.add(dto);
      return list;
   }

}