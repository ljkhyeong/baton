package com.personal.baton.buildlogic;

import com.epages.restdocs.apispec.model.ResourceModel;
import com.epages.restdocs.apispec.openapi3.OpenApi3Generator;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.module.kotlin.ExtensionsKt;

@CacheableTask
public abstract class OpenApi3ContractTask extends DefaultTask {

	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getSnippetsDirectory();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@Input
	public abstract Property<String> getServerUrl();

	@Input
	public abstract Property<String> getTitle();

	@Input
	public abstract Property<String> getDocumentDescription();

	@Input
	public abstract Property<String> getApiVersion();

	@Input
	public abstract Property<String> getFormat();

	@TaskAction
	public void generateContract() throws IOException {
		Path snippetsDirectory = getSnippetsDirectory().get().getAsFile().toPath();
		List<Path> resourcePaths;
		try (var paths = Files.walk(snippetsDirectory)) {
			resourcePaths = paths
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equals("resource.json"))
					.sorted(Comparator.comparing(path -> snippetsDirectory.relativize(path).toString()))
					.toList();
		}

		if (resourcePaths.isEmpty()) {
			throw new GradleException("OpenAPI를 생성할 REST Docs resource snippet이 없습니다.");
		}

		ObjectMapper objectMapper = ExtensionsKt.jacksonMapperBuilder()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();
		List<ResourceModel> resources = new ArrayList<>(resourcePaths.size());
		for (Path resourcePath : resourcePaths) {
			resources.add(objectMapper.readValue(resourcePath.toFile(), ResourceModel.class));
		}

		String contract = OpenApi3Generator.INSTANCE.generateAndSerialize(
				resources,
				List.of(new Server().url(getServerUrl().get())),
				getTitle().get(),
				getDocumentDescription().get(),
				Map.of(),
				getApiVersion().get(),
				null,
				getFormat().get(),
				null
		);

		Path outputFile = getOutputFile().get().getAsFile().toPath();
		Files.createDirectories(outputFile.getParent());
		Files.writeString(outputFile, contract, StandardCharsets.UTF_8);
	}
}
