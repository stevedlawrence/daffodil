/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const fs = require("fs");
const os = require("os");
const core = require("@actions/core");
const github = require("@actions/github");
const { exec } = require('@actions/exec');

async function run() {
	const tlp_dir = core.getInput("tlp_dir");
	const project_id = core.getInput("project_id");
	const project_dir = core.getInput("project_dir");
	const gpg_signing_key = core.getInput("gpg_signing_key");
	const nexus_username = core.getInput("nexus_username");
	const nexus_password = core.getInput("nexus_password");
	let publish = core.getBooleanInput("publish");

	// import signing key into gpg and get it's key id
	let gpg_import_stdout = ""
	await exec("gpg", ["--batch", "--import", "--import-options", "import-show"], {
		input: Buffer.from(gpg_signing_key),
		listeners: {
			stdout: (data) => { gpg_import_stdout += data.toString(); }
		}
	});
	const gpg_signing_key_id = gpg_import_stdout.match("[0-9A-Z]{40}")[0];
	console.info("Using gpgp key id: " + gpg_signing_key_id);

	// tags must be signed with a commiters key, download and import committer
	// keys for verification later
	let committer_keys = "";
	await exec("curl", [`https://downloads.apache.org/${ tlp_dir }/KEYS`], {
		silent: true,
		listeners: {
			stdout: (data) => { committer_keys += data.toString(); }
		}
	});
	await exec("gpg", ["--batch", "--import"], {
		input: Buffer.from(committer_keys)
	});

	// get the actual project version from the source build configuration--this
	// does not have a leading 'v' or -rcX suffix, but might have a -SNAPSHOT
	// suffix. Note that regex stuff is a bit specific Daffodil projects. We
	// may want to consider requiring projects using this to have a VERSION
	// file, and it's up to projects to keep that file in sync with the build
	// configuration--most configs can probably just read this file. This is
	// really the only part of this action that is specific to Daffodil.
	// Everything else would likely work for other ASF projects.
	let project_version = "";
	if (fs.existsSync("package.json")) {
		project_version = fs.readFileSync("package.json").toString().match(/"version": "(.*)"/)[1];
	} else if (fs.existsSync("build.sbt")) {
		project_version = fs.readFileSync("build.sbt").toString().match(/version := "(.*)"/)[1];
	} else {
		core.setFailed("Could not determine project version from package.json or build.sbt");
	}

	let release_version = "";
	if (github.event_name == "push" && github.ref.includes("refs/tags")) {
		// this was triggered by the push of a tag, the tag name will be the
		// version used
		release_version = github.ref_name

		// make sure the tag name matches the actual project version
		if (!release_version.startsWith(`v${project_version}-`)) {
			core.setFailed(`Tag ${ release_version } does not match project version: v${ project_version }`);
		}

		// make sure the tag is signed by a committer, this command fails if the
		// tag does not verify. Note that the github checkout action does not fetch
		// tag information when triggered from a tag, so we must fetch it manually
		await exec("git", ["fetch", "origin", "--deepen=1", `+${ release_version }:${ release_version }`]);
		await exec("git", ["tag", "--verify", release_version]);
	} else {
		// this was not triggered by a tag, maybe is was manually triggered via
		// workflow_dispatch or a normal commit. We should only publish from tags,
		// so we disable publishing. We also set the release_version so that it has the
		// same format as a tag (e.g. v1.2.3-rc1)
		core.warning("Action not triggered from tag, publishing disabled");
		release_version = `v${ project_version }-rc0`;
		publish = false;
	}

	const is_snapshot = project_version.includes("-SNAPSHOT");

	// disable publishing for snapshot builds or non-ASF builds. Note that
	// publishing could still be disabled if the publish input was explicitly set
	// to false
	if (publish && (is_snapshot || github.repository_owner != "apache")) {
		core.warning("Publishing disabled for snapshot versions and from non-apache repositories");
		publish = false;
	}

	const release_candidate_dir = `${ os.tmpdir() }/release-candidate`;
	fs.mkdirSync(release_candidate_dir);

	// enable and configure SBT for signing and publishing. Note that the
	// sbt-pgp plugin version should not be updated unless there is a
	// compelling reason. Release signing has been known to break with newer
	// versions.
	const sbt_dir = `${ os.homedir }/.sbt/1.0`
	fs.mkdirSync(`${ sbt_dir }/plugins`, { recursive: true });
	fs.appendFileSync(`${ sbt_dir }/plugins/build.sbt`, 'addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")\n');
	fs.appendFileSync(`${ sbt_dir }/build.sbt`, `pgpSigningKey := Some("${ gpg_signing_key_id }")\n`);

	if (publish) {
		// if publishing is enabled, publishing to the apache staging repository
		// with the provided credentials. We must diable gigahorse since that fails
		// to publish on some systems
		fs.appendFileSync(`${ sbt_dir }/build.sbt`, 'ThisBuild / updateOptions := updateOptions.value.withGigahorse(false)\n');
		fs.appendFileSync(`${ sbt_dir }/build.sbt`, `ThisBuild / credentials += Credentials("Sonatype Nexus Repository Manager", "repository.apache.org", "${ nexus_username }", "${ nexus_password }")\n`);
		fs.appendFileSync(`${ sbt_dir }/build.sbt`, 'ThisBuild / publishTo := Some("Apache Staging Distribution Repository" at "https://repository.apache.org/service/local/staging/deploy/maven2")\n');
	} else {
		// if publishing is not enabled, we still want the ability for workflows to
		// run 'sbt publishSigned' so they don't have to change logic depending on
		// if they are publishing or not. To support this, configure sbt to publish
		// to a local maven repo
		const maven_local_dir = `${ release_candidate_dir }/maven-local`;
		fs.mkdirSync(maven_local_dir);
		fs.appendFileSync(`${ sbt_dir }/build.sbt`, `ThisBuild / publishTo := Some(MavenCache("maven-local", file("${ maven_local_dir }")))\n`);
	}

	// checkout artifact dist directory
	const project_dist_dir = `${ release_candidate_dir }/asf-dist`;
	await exec("svn", ["checkout", `https://dist.apache.org/repos/dist/dev/${ tlp_dir }/${ project_dir }`, project_dist_dir]);

	// remove previous release candidates of this version (i.e. any directories
	// that have the same project_version followed by a hyphen)
	const direntries = fs.readdirSync(project_dist_dir, { withFileTypes: true });
	for(const dirent of direntries) {
		if (dirent.isDirectory && dirent.name.startsWith(`${ project_version }-`)) {
			await exec("svn", ["delete", "--force", `${ dirent.parentPath }/${ dirent.name }`]);
		}
	}

	// create the directory for artifacts, this is the version without the leading
	// 'v', but keeping any -rcX or -SNAPSHOT suffixes
	const artifact_dir = `${ project_dist_dir }/${ release_version.slice(1) }`;
	fs.mkdirSync(artifact_dir);

	// create the source artifact
	const src_artifact_dir = `${ artifact_dir }/src`;
	const src_artifact_name = `apache-${ project_id }-${ project_version }-src`;
	fs.mkdirSync(src_artifact_dir);
	await exec("git", ["archive", "--format=zip", `--prefix=${ src_artifact_name }/`, "--output", `${ src_artifact_dir }/${ src_artifact_name }.zip`, "HEAD"]);

	// get the reproducible build epoch
	let source_date_epoch = "";
	await exec("git", ["show", "--no-patch", "--format=%ct", "HEAD"], {
		listeners: {
			stdout: (data) => { source_date_epoch += data.toString().trim(); }
		}
	});

	// we are done with all the filesystem setup, we now export environment
	// variables, output variables, and state needed by the post script

	// export environment variables
	core.exportVariable("SOURCE_DATE_EPOCH", source_date_epoch);

	// export step output variables
	core.setOutput("artifact_dir", artifact_dir);

	// export state information for the post step
	core.saveState("artifact_dir", artifact_dir);
	core.saveState("gpg_signing_key_id", gpg_signing_key_id);
	core.saveState("publish", publish);
	core.saveState("release_version", release_version);
}

run();
