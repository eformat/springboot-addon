/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.springboot.commands.setup;

import static org.jboss.forge.addon.maven.archetype.ArchetypeHelper.recursiveDelete;
import static org.jboss.forge.addon.springboot.utils.ConvertHelper.jsonToMap;
import static org.jboss.forge.addon.springboot.utils.ConvertHelper.removeDoubleQuotes;
import static org.jboss.forge.addon.springboot.utils.IOHelper.close;
import static org.jboss.forge.addon.springboot.utils.IOHelper.copyAndCloseInput;
import static org.jboss.forge.addon.springboot.utils.UnzipHelper.unzip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.ws.rs.client.Client;

import org.jboss.forge.addon.configuration.Configuration;
import org.jboss.forge.addon.facets.FacetFactory;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.facets.MetadataFacet;
import org.jboss.forge.addon.projects.facets.ResourcesFacet;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.addon.rest.ClientFactory;
import org.jboss.forge.addon.springboot.SpringBootFacet;
import org.jboss.forge.addon.springboot.dto.SpringBootDependencyDTO;
import org.jboss.forge.addon.springboot.utils.CollectionStringBuffer;
import org.jboss.forge.addon.springboot.utils.SpringBootHelper;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectMany;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.output.UIOutput;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Commands;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizardStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class SetupProjectCommand extends AbstractSpringBootCommand implements UIWizardStep
{

   private static final transient Logger LOG = LoggerFactory.getLogger(SetupProjectCommand.class);

   // lets use a different category for this command
   private static final String CATEGORY = "Spring Boot";

   private static String SPRING_BOOT_CONFIG_FILE;
   private static String SPRING_BOOT_DEFAULT_VERSION;
   private static List<String> SPRING_BOOT_VERSIONS;
   private static final List<String> DEFAULT_SPRING_BOOT_VERSIONS = new ArrayList<>(5);
   private static final String LATEST_STABLE_SPRING_BOOT_VERSION = "1.5.4.RELEASE";
   private static final String RHOAR_SUPPORTED_VERSION = "1.4.1.RELEASE";
   private static final String LATEST_1_4_SPRING_BOOT_VERSION = "1.4.7.RELEASE";
   private static final String LATEST_2_0_VERSION = "2.3.3.RELEASE";

   static {
      Collections.addAll(DEFAULT_SPRING_BOOT_VERSIONS, RHOAR_SUPPORTED_VERSION, LATEST_1_4_SPRING_BOOT_VERSION,
               LATEST_STABLE_SPRING_BOOT_VERSION, LATEST_2_0_VERSION);
   }

   private static final String STARTER_ZIP_URL = "/starter.zip";
   private static final String STARTER_URL = "https://start.spring.io";
   private static List deps = new ArrayList<>();

   public SetupProjectCommand()
   {
      if (SPRING_BOOT_DEFAULT_VERSION == null) {
         final String bootDefaultVersion = System.getenv("SPRING_BOOT_DEFAULT_VERSION");
         SPRING_BOOT_DEFAULT_VERSION = bootDefaultVersion != null ? bootDefaultVersion
                  : LATEST_STABLE_SPRING_BOOT_VERSION;
      }
      if (SPRING_BOOT_VERSIONS == null || SPRING_BOOT_VERSIONS.isEmpty()) {
         final String bootVersions = System.getenv("SPRING_BOOT_VERSIONS");
         SPRING_BOOT_VERSIONS = bootVersions != null ? splitVersions(bootVersions) : DEFAULT_SPRING_BOOT_VERSIONS;
      }

      if (SPRING_BOOT_CONFIG_FILE == null) {
         SPRING_BOOT_CONFIG_FILE = System.getenv("SPRING_BOOT_CONFIG_FILE");
      }
   }

   @Inject
   @WithAttributes(label = "Spring Boot Version", description = "Spring Boot Version to use")
   private UISelectOne<String> springBootVersion;

   @Inject
   @WithAttributes(label = "Create static content?", description = "Should we create static content?", defaultValue =
         "false")
   private UIInput<Boolean> createStaticContent;

   @Inject
   @WithAttributes(label = "Port", description = "The port on which the application will run", defaultValue =
         "8080")
   private UIInput<Integer> port;

   @Inject
   @WithAttributes(label = "Dependencies", description = "Add Spring Boot Starters and dependencies to your application")
   private UISelectMany<SpringBootDependencyDTO> dependencies;

   @Inject
   private ClientFactory factory;

   @Inject
   private FacetFactory facetFactory;

   @Inject
   private Configuration configuration;

   private List<SpringBootDependencyDTO> choices;

   private static List<String> splitVersions(String s)
   {
      return Arrays.stream(s.split(","))
            .distinct()
            .filter(element -> !element.isEmpty())
            .collect(Collectors.toList());
   }

   @Override
   public void initializeUI(UIBuilder builder) throws Exception
   {
      UIOutput uiOutput = builder.getUIContext().getProvider().getOutput();
      springBootVersion.setValueChoices(SPRING_BOOT_VERSIONS);
      springBootVersion.setDefaultValue(SPRING_BOOT_DEFAULT_VERSION);

      try
      {
         choices = initDependencies(uiOutput);
      }
      catch (Exception e)
      {
         e.printStackTrace();
         throw new IllegalStateException(
                  "Error loading dependencies from spring-boot-application.yaml due: "
                           + e.getMessage(), e);
      }

      dependencies.setValueChoices(choices);
      if (builder.getUIContext().getProvider().isGUI())
      {
         dependencies.setItemLabelConverter(SpringBootDependencyDTO::getGroupAndName);
      }
      else
      {
         // if in CLI mode then use shorter names so they are tab friendly in the shell
         dependencies.setItemLabelConverter(
                  dto -> Commands.shellifyCommandName(dto.getName()));
      }

      dependencies.setValueConverter(s ->
      {
         for (SpringBootDependencyDTO dto : choices)
         {
            if (dto.getId().equals(s))
            {
               return dto;
            }
         }
         return null;
      });

      builder.add(springBootVersion).add(dependencies).add(createStaticContent).add(port);
   }

   private List<SpringBootDependencyDTO> initDependencies(UIOutput uiOutput) throws Exception
   {
      List<SpringBootDependencyDTO> list = new ArrayList<>();

      for (Object dep : fetchDependencies(uiOutput))
      {
         Map<String,Object> group = (Map) dep;
         String groupName = removeDoubleQuotes(group.get("name"));
         List<Map<String,String>> content;
         // Add this test as the json file & yaml file uses a different key
         if (group.get("content") != null)
         {
            content = (List) group.get("content");
         }
         else
         {
            content = (List) group.get("values");
         }
         for (Object row : content)
         {
            Map<String, Object> item = (Map) row;
            String id = removeDoubleQuotes(item.get("id"));
            String name = removeDoubleQuotes(item.get("name"));
            String description = removeDoubleQuotes(item.get("description"));
            list.add(new SpringBootDependencyDTO(groupName, id, name, description));
         }
      }
      return list;
   }

   @Override
   public UICommandMetadata getMetadata(UIContext context)
   {
      return Metadata.from(super.getMetadata(context), getClass())
               .category(Categories.create(CATEGORY)).name(CATEGORY + ": Setup")
               .description("Create a new Spring Boot project");
   }

   @Override
   public Result execute(UIExecutionContext context) throws Exception
   {
      UIContext uiContext = context.getUIContext();
      UIOutput uiOutput = uiContext.getProvider().getOutput();
      Project project = (Project) uiContext.getAttributeMap().get(Project.class);
      if (project == null)
      {
         project = getSelectedProject(context.getUIContext());
      }

      // install the SpringBootFacet
      facetFactory.install(project, SpringBootFacet.class);

      MetadataFacet metadataFacet = project.getFacet(MetadataFacet.class);
      String projectName = metadataFacet.getProjectName();
      String projectGroupId = metadataFacet.getProjectGroupName();
      String projectVersion = metadataFacet.getProjectVersion();
      File folder = project.getRoot().reify(DirectoryResource.class).getUnderlyingResourceObject();

      Map<String, SpringBootDependencyDTO> selectedDTOs = new HashMap<>();
      int[] selected = dependencies.getSelectedIndexes();
      CollectionStringBuffer csbSpringBoot = new CollectionStringBuffer(",");
      for (int val : selected)
      {
         SpringBootDependencyDTO dto = choices.get(val);
         csbSpringBoot.append(dto.getId());
         selectedDTOs.put(dto.getId(), dto);
      }
      String springBootDeps = csbSpringBoot.toString();

      // boot version need the RELEASE suffix
      String bootVersion = springBootVersion.getValue();

      String url = String
               .format("%s?bootVersion=%s&groupId=%s&artifactId=%s&version=%s&packageName=%s&dependencies=%s",
                        getStarterURL() + STARTER_ZIP_URL, bootVersion, projectGroupId, projectName, projectVersion, projectGroupId,
                        springBootDeps);

      LOG.info("About to query url: " + url);
      LOG.info("Version of the User Agent:" + USER_AGENT);
      uiOutput.info(uiOutput.out(), "About to query spring starter: " + url);

      Client client = factory.createClient();
      InputStream is = client.target(url)
                              .request()
                              .header("User-Agent",USER_AGENT)
                              .get(InputStream.class);

      // some archetypes might not use maven or use the maven source layout so lets remove
      // the pom.xml and src folder if its already been pre-created
      // as these will be created if necessary via the archetype jar's contents
      File pom = new File(folder, "pom.xml");
      if (pom.isFile() && pom.exists())
      {
         pom.delete();
      }
      File src = new File(folder, "src");
      if (src.isDirectory() && src.exists())
      {
         recursiveDelete(src);
      }

      File name = new File(folder, projectName + ".zip");
      if (name.exists())
      {
         name.delete();
      }

      FileOutputStream fos = new FileOutputStream(name, false);
      copyAndCloseInput(is, fos);
      close(fos);
      client.close();

      // unzip the download from spring starter
      unzip(name, folder);

      LOG.info("Unzipped file to folder: {}", folder.getAbsolutePath());
      uiOutput.info(uiOutput.out(),
               "Unzipped file to folder: " + folder.getAbsolutePath());

      // and delete the zip file
      name.delete();

      // set port if needed
      final Integer portValue = port.getValue();
      if (portValue != 8080) {
         final Properties properties = new Properties();
         properties.put("server.port", portValue.toString());
         SpringBootHelper.writeToApplicationProperties(project, properties);
      }

      final Boolean createStatic = createStaticContent.getValue();
      if (createStatic) {
         // create static sub-directory
         final ResourcesFacet resourcesFacet = project.getFacet(ResourcesFacet.class);
         final FileResource<?> staticDir = resourcesFacet.getResource("static");
         if (!staticDir.exists()) {
            staticDir.mkdirs();
         }

         // add SpringBootServletInitializer to generated Spring Boot application
         SpringBootHelper.modifySpringBootApplication(project, sbApp -> {
            sbApp.addImport("org.springframework.boot.builder.SpringApplicationBuilder");
            sbApp.setSuperType("org.springframework.boot.web.support.SpringBootServletInitializer");
            sbApp.addMethod("@Override\n" +
                  "               protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {\n" +
                  "                  return application.sources(" + sbApp.getName() + ".class);\n" +
                  "               }");

            return sbApp;
         });

         // add web dependency
         SpringBootHelper.addSpringBootDependency(project, SpringBootFacet.SPRING_BOOT_STARTER_WEB_ARTIFACT);
      }

      // are there any fabric8 dependencies to add afterwards?
      return Results.success(
               "Created new Spring Boot project in directory: " + folder.getName());
   }

   private String getStarterURL()
   {
        return configuration.getString("springboot.starterurl", STARTER_URL);
   }

   private List fetchDependencies(UIOutput uiOutput) throws Exception
   {
      if(deps.size() > 0) {
         return deps;
      } else {
         // Check if we have a Spring Boot Config File
         if (SPRING_BOOT_CONFIG_FILE != null)
         {
            uiOutput.info(uiOutput.out(),"Use spring boot yaml config file");
                     Yaml yaml = new Yaml();
            InputStream input = new URL(SPRING_BOOT_CONFIG_FILE).openStream();
            Map<String, Map> data = (Map) yaml.load(input);
            Map<String,List<Map>> initializer = (Map) data.get("initializr");
            deps = initializer.get("dependencies");
         }
         else
         {
            // Fetch the dependencies list from the start.spring.io server
            uiOutput.info(uiOutput.out(),"Fetch deps from start.spring.io");
            Client client = factory.createClient();
            String response = client.target(getStarterURL())
                     .request()
                     .header("User-Agent",USER_AGENT)
                     .get(String.class);
            client.close();
            Map<String,Object> data = jsonToMap(response);
            Map<String,List<Map>> dependencies = (Map) data.get("dependencies");
            deps = dependencies.get("values");
         }
         return deps;
      }
   }
}
