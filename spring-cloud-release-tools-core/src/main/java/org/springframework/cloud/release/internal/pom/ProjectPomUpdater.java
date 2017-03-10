/*
 *  Copyright 2013-2017 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.springframework.cloud.release.internal.pom;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.release.internal.ReleaserProperties;
import org.springframework.cloud.release.internal.git.ProjectGitUpdater;

/**
 * @author Marcin Grzejszczak
 */
public class ProjectPomUpdater {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final ReleaserProperties properties;
	private final ProjectGitUpdater gitRepo;
	private final PomUpdater pomUpdater = new PomUpdater();

	public ProjectPomUpdater(ReleaserProperties properties) {
		this.properties = properties;
		this.gitRepo = new ProjectGitUpdater(properties);
	}

	/**
	 * For the given root folder (typically the working directory) performs the whole
	 * flow of updating {@code pom.xml} with values from Spring Cloud Release project.
	 *
	 * @param projectRoot - root folder with project to update
	 */
	public void updateProjectFromSCRelease(File projectRoot) {
		File clonedScRelease = this.gitRepo.cloneScReleaseProject();
		this.gitRepo.checkout(clonedScRelease, this.properties.getPom().getBranch());
		SCReleasePomParser sCReleasePomParser = new SCReleasePomParser(clonedScRelease);
		Versions versions = sCReleasePomParser.allVersions();
		log.info("Retrieved the following versions\n{}", versions);
		if (!this.pomUpdater.shouldProjectBeUpdated(projectRoot, versions)) {
			log.info("Skipping project updating");
			return;
		}
		updatePoms(projectRoot, versions);
	}

	private void updatePoms(File projectRoot, Versions versions) {
		File rootPom = new File(projectRoot, "pom.xml");
		ModelWrapper rootPomModel = this.pomUpdater.readModel(rootPom);
		processAllPoms(projectRoot, new PomWalker(rootPomModel, versions, this.pomUpdater,
				this.properties));
	}

	public void updatePomsForRootVersion(File directory, String version) {
		File pom = new File(directory, "pom.xml");
		Versions versions = versions(version, pom);
		updatePoms(directory, versions);
	}

	private Versions versions(String version, File pom) {
		ModelWrapper model = this.pomUpdater.readModel(pom);
		Set<Project> projects = new HashSet<>();
		projects.add(new Project(model.projectName(), version));
		return new Versions("", "", projects);
	}

	private void processAllPoms(File projectRoot, PomWalker pomWalker) {
		try {
			Files.walkFileTree(projectRoot.toPath(), pomWalker);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private class PomWalker extends SimpleFileVisitor<Path> {

		private static final String POM_XML = "pom.xml";

		private final ModelWrapper rootPom;
		private final Versions versions;
		private final PomUpdater pomUpdater;
		private final ReleaserProperties properties;

		private PomWalker(ModelWrapper rootPom, Versions versions, PomUpdater pomUpdater,
				ReleaserProperties properties) {
			this.rootPom = rootPom;
			this.versions = versions;
			this.pomUpdater = pomUpdater;
			this.properties = properties;
		}

		@Override
		public FileVisitResult visitFile(Path path, BasicFileAttributes attr) {
			File file = path.toFile();
			if (POM_XML.equals(file.getName())) {
				if (pathIgnored(file)) {
					log.debug("Ignoring file [{}] since it's on a list of patterns to ignore", file);
					return FileVisitResult.CONTINUE;
				}
				ModelWrapper model = this.pomUpdater.updateModel(this.rootPom, file, this.versions);
				this.pomUpdater.overwritePomIfDirty(model, this.versions, file);
			}
			return FileVisitResult.CONTINUE;
		}

		private boolean pathIgnored(File file) {
			String path = file.getPath();
			return this.properties.getPom().getIgnoredPomRegex().stream().anyMatch(path::matches);
		}
	}

}
