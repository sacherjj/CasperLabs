use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::Path,
    process::{self, Command},
    str,
};

use colour::red;

use super::{FAILURE_EXIT_CODE, ROOT_PATH};

pub const CL_CONTRACT_VERSION: &str = "0.22.0";

pub fn print_error_and_exit(msg: &str) -> ! {
    red!("error");
    eprintln!("{}", msg);
    process::exit(FAILURE_EXIT_CODE)
}

pub fn run_cargo_new(package_name: &str) {
    let mut command = Command::new("cargo");
    command
        .args(&["new", "--vcs", "none"])
        .arg(package_name)
        .current_dir(&*ROOT_PATH);

    let output = match command.output() {
        Ok(output) => output,
        Err(error) => print_error_and_exit(&format!(": failed to run '{:?}': {}", command, error)),
    };

    if !output.status.success() {
        let stdout = str::from_utf8(&output.stdout).expect("should be valid UTF8");
        let stderr = str::from_utf8(&output.stderr).expect("should be valid UTF8");
        print_error_and_exit(&format!(
            ": failed to run '{:?}':\n{}\n{}\n",
            command, stdout, stderr
        ));
    }
}

pub fn create_dir_all<P: AsRef<Path>>(path: P) {
    if let Err(error) = fs::create_dir_all(path.as_ref()) {
        print_error_and_exit(&format!(
            ": failed to create '{}': {}",
            path.as_ref().display(),
            error
        ));
    }
}

pub fn write_file<P: AsRef<Path>, C: AsRef<[u8]>>(path: P, contents: C) {
    if let Err(error) = fs::write(path.as_ref(), contents) {
        print_error_and_exit(&format!(
            ": failed to write to '{}': {}",
            path.as_ref().display(),
            error
        ));
    }
}

pub fn append_to_file<P: AsRef<Path>, C: AsRef<[u8]>>(path: P, contents: C) {
    let mut file = match OpenOptions::new().append(true).open(path.as_ref()) {
        Ok(file) => file,
        Err(error) => {
            print_error_and_exit(&format!(
                ": failed to open '{}': {}",
                path.as_ref().display(),
                error
            ));
        }
    };
    if let Err(error) = file.write_all(contents.as_ref()) {
        print_error_and_exit(&format!(
            ": failed to append to '{}': {}",
            path.as_ref().display(),
            error
        ));
    }
}

pub fn remove_file<P: AsRef<Path>>(path: P) {
    if let Err(error) = fs::remove_file(path.as_ref()) {
        print_error_and_exit(&format!(
            ": failed to remove '{}': {}",
            path.as_ref().display(),
            error
        ));
    }
}

#[cfg(test)]
pub mod tests {
    use std::{env, fs};

    use toml::Value;

    use super::CL_CONTRACT_VERSION;

    const CL_CONTRACT_TOML_PATH: &str = "contract/Cargo.toml";
    const PACKAGE_FIELD_NAME: &str = "package";
    const VERSION_FIELD_NAME: &str = "version";

    /// Returns the absolute path of `relative_path` where this is relative to "execution-engine".
    /// Panics if the current working directory is not within "execution-engine".
    pub fn full_path_from_path_relative_to_ee(relative_path: &str) -> String {
        let mut full_path = env::current_dir().unwrap().display().to_string();
        let index = full_path
            .find("/execution-engine/")
            .expect("test should be run from within execution-engine workspace");
        full_path.replace_range(index + 18.., relative_path);
        full_path
    }

    /// Checks the version of the package specified by the Cargo.toml at `toml_path` is equal to
    /// the hard-coded one specified in `local_version`.
    pub fn check_package_version(local_version: &str, toml_path: &str) {
        let toml_path = full_path_from_path_relative_to_ee(toml_path);

        let raw_toml_contents =
            fs::read(&toml_path).unwrap_or_else(|_| panic!("should read {}", toml_path));
        let toml_contents = String::from_utf8_lossy(&raw_toml_contents).to_string();
        let toml = toml_contents.parse::<Value>().unwrap();

        let expected_version = toml[PACKAGE_FIELD_NAME][VERSION_FIELD_NAME]
            .as_str()
            .unwrap();
        // If this fails, ensure `local_version` is updated to match the value in the Cargo.toml at
        // `toml_path`.
        assert_eq!(
            expected_version, local_version,
            "\n\nEnsure local version is updated to {} as defined in {}\n\n",
            expected_version, toml_path
        );
    }

    #[test]
    fn check_cl_contract_version() {
        check_package_version(CL_CONTRACT_VERSION, CL_CONTRACT_TOML_PATH);
    }
}
