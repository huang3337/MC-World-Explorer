#![cfg(windows)]

use mcwe_core::WorldSource;
use std::ffi::OsStr;
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::os::windows::fs::MetadataExt;
use std::path::{Path, PathBuf};

const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;

#[test]
#[ignore = "requires an explicitly approved real world path in MCWE_REAL_WORLD"]
fn reads_real_world_files_through_public_api() {
    let root = PathBuf::from(
        std::env::var_os("MCWE_REAL_WORLD")
            .expect("MCWE_REAL_WORLD must contain the approved absolute world path"),
    );
    assert!(root.is_absolute(), "MCWE_REAL_WORLD must be absolute");

    let source = WorldSource::new(&root).expect("approved real world root must be readable");

    let mut level = source
        .open_file("level.dat")
        .expect("level.dat must be readable through WorldSource");
    let mut level_prefix = [0_u8; 8192];
    let level_count = level
        .read(&mut level_prefix)
        .expect("level.dat read must succeed");
    assert!(level_count > 0, "level.dat must not be empty");

    let region_name = first_plain_region_file(&root.join("region"));
    let relative_region = Path::new("region").join(&region_name);
    let mut region = source
        .open_file(&relative_region)
        .expect("selected Region file must be readable through WorldSource");

    let mut first = [0_u8; 8192];
    let count = region
        .read(&mut first)
        .expect("initial Region read must succeed");
    assert!(count > 0, "selected Region file must not be empty");

    region
        .seek(SeekFrom::Start(0))
        .expect("Region seek must succeed");
    let mut second = vec![0_u8; count];
    region
        .read_exact(&mut second)
        .expect("repeated Region read must succeed");
    assert_eq!(&first[..count], second);

    println!("MCWE_REAL_REGION={}", relative_region.display());
}

fn first_plain_region_file(region_dir: &Path) -> std::ffi::OsString {
    let directory_metadata =
        fs::symlink_metadata(region_dir).expect("the approved world must contain region/");
    assert!(directory_metadata.is_dir(), "region/ must be a directory");
    assert_plain(&directory_metadata, "region/ must not be a reparse point");

    let mut candidates = Vec::new();
    for entry in fs::read_dir(region_dir).expect("region/ must be enumerable") {
        let entry = entry.expect("every region/ entry must be enumerable");
        let metadata =
            fs::symlink_metadata(entry.path()).expect("every region/ entry must have metadata");
        assert_plain(&metadata, "region/ must not contain reparse points");
        if metadata.is_file()
            && entry
                .path()
                .extension()
                .and_then(OsStr::to_str)
                .is_some_and(|extension| extension.eq_ignore_ascii_case("mca"))
        {
            candidates.push(entry.file_name());
        }
    }

    candidates.sort();
    candidates
        .into_iter()
        .next()
        .expect("region/ must contain at least one ordinary .mca file")
}

fn assert_plain(metadata: &fs::Metadata, message: &str) {
    assert_eq!(
        metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT,
        0,
        "{message}"
    );
}
