use anyhow::{Context, Result, ensure};
use chrono::Local;
use clap::Args;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

use crate::boot_patch;

#[derive(Args, Debug)]
pub struct UnpackArgs {
    /// boot image path, if not specified, will try to find the boot image automatically
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// output path, if not specified, will use current directory
    #[arg(short, long)]
    pub out: Option<PathBuf>,
}

#[derive(Args, Debug)]
pub struct RepackArgs {
    /// unpacked boot image path (zip file) or kernel image
    #[arg(short, long)]
    pub source: PathBuf,

    /// original boot image path to repack into (if not specified, tries to find active boot)
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// output path, if not specified, will use current directory
    #[arg(short, long)]
    pub out: Option<PathBuf>,
}

pub fn unpack(args: UnpackArgs) -> Result<()> {
    let tmpdir = tempfile::Builder::new()
        .prefix("MagiskbootUnpack")
        .tempdir()
        .context("create temp dir failed")?;
    let workdir = tmpdir.path();

    let magiskboot = boot_patch::find_magiskboot(None, workdir)?;

    let kmi = boot_patch::get_current_kmi().unwrap_or_default();
    let (bootimage, _) =
        boot_patch::find_boot_image(&args.boot, &kmi, false, false, workdir, &None)?;

    println!("- Unpacking boot image: {}", bootimage.display());

    let unpack_dir = workdir.join("unpacked");
    fs::create_dir_all(&unpack_dir)?;

    let status = Command::new(&magiskboot)
        .current_dir(&unpack_dir)
        .arg("unpack")
        .arg(&bootimage)
        .status()?;
    ensure!(status.success(), "magiskboot unpack failed");

    let now = Local::now();
    let filename = format!("boot.img-unpacked-{}.zip", now.format("%Y%m%d-%H%M%S"));
    let out_dir = args.out.unwrap_or_else(|| PathBuf::from("."));
    fs::create_dir_all(&out_dir)?;
    let out_path = out_dir.join(&filename);

    println!("- Creating archive: {}", out_path.display());

    let file = fs::File::create(&out_path)?;
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::FileOptions::<()>::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .unix_permissions(0o755);

    for entry in fs::read_dir(&unpack_dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file() {
            let name = path.file_name().unwrap().to_string_lossy();
            zip.start_file(name, options)?;
            let mut f = fs::File::open(path)?;
            std::io::copy(&mut f, &mut zip)?;
        }
    }
    zip.finish()?;

    println!("- Done!");
    Ok(())
}

pub fn repack(args: RepackArgs) -> Result<()> {
    let tmpdir = tempfile::Builder::new()
        .prefix("MagiskbootRepack")
        .tempdir()
        .context("create temp dir failed")?;
    let workdir = tmpdir.path();

    let magiskboot = boot_patch::find_magiskboot(None, workdir)?;

    let kmi = boot_patch::get_current_kmi().unwrap_or_default();
    let (bootimage, _) =
        boot_patch::find_boot_image(&args.boot, &kmi, false, false, workdir, &None)?;

    println!("- Using base boot image: {}", bootimage.display());

    // Unpack original boot image to get context for repack
    let status = Command::new(&magiskboot)
        .current_dir(workdir)
        .arg("unpack")
        .arg(&bootimage)
        .status()?;
    ensure!(status.success(), "magiskboot unpack failed");

    // Process source
    println!("- Processing source: {}", args.source.display());
    let source_path = args.source;
    if source_path.extension().map_or(false, |ext| ext == "zip") {
        // Extract zip
        let file = fs::File::open(&source_path)?;
        let mut archive = zip::ZipArchive::new(file)?;

        // Find kernel image in zip
        let kernel_names = ["Image", "Image.gz", "Image.lz4", "kernel"];
        let mut found = false;

        for i in 0..archive.len() {
            let mut file = archive.by_index(i)?;
            let name = file.name().to_string();
            // simple check for filename match (ignore directories)
            if file.is_file() && kernel_names.contains(&name.as_str()) {
                println!("- Found kernel candidate: {}", name);
                let target = workdir.join("kernel");
                let mut out = fs::File::create(&target)?;
                std::io::copy(&mut file, &mut out)?;
                found = true;
                break;
            }
        }
        ensure!(
            found,
            "No kernel image found in zip (looked for: {:?})",
            kernel_names
        );
    } else {
        // Assume source IS the kernel image
        fs::copy(&source_path, workdir.join("kernel"))?;
    }

    // Repack
    println!("- Repacking boot image");
    let status = Command::new(&magiskboot)
        .current_dir(workdir)
        .arg("repack")
        .arg(&bootimage)
        .status()?;
    ensure!(status.success(), "magiskboot repack failed");

    // Find new-boot.img
    let new_boot = workdir.join("new-boot.img");
    ensure!(new_boot.exists(), "new-boot.img not found after repack");

    let now = Local::now();
    let filename = format!("boot.img-repacked-{}.img", now.format("%Y%m%d-%H%M%S"));
    let out_dir = args.out.unwrap_or_else(|| PathBuf::from("."));
    fs::create_dir_all(&out_dir)?;
    let out_path = out_dir.join(&filename);

    fs::copy(&new_boot, &out_path)?;
    println!("- Repacked image saved to: {}", out_path.display());

    Ok(())
}
