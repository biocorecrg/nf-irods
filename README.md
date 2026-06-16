# nf-irods

A Nextflow plugin for reading and writing files in iRODS natively using the `irods://` URI scheme.

## How to Build

To compile and package the plugin, run:
```bash
make assemble
```
This compiles the Groovy sources and packages the plugin into a ZIP file in the `build/plugins/` directory.

## How to Install (Local Testing)

Extract the packaged plugin into your local Nextflow plugins directory:
```bash
mkdir -p ~/.nextflow/plugins
unzip -o build/plugins/nf-irods-*.zip -d ~/.nextflow/plugins/
```

## How to Configure

In your `nextflow.config`, you simply need to enable the plugin:

```nextflow
plugins {
    id 'nf-irods'
}
```

**IMPORTANT**: The plugin automatically reads your standard iRODS configuration from `$HOME/.irods/irods_environment.json`. You must ensure this file exists and contains your connection details (host, port, zone, username, etc.).

The plugin also supports standard environment variables as fallbacks if needed:
- `IRODS_HOST`
- `IRODS_PORT`
- `IRODS_USER_NAME`
- `IRODS_PASSWORD`
- `IRODS_ZONE_NAME`
- `IRODS_DEFAULT_RESOURCE`

## Authentication

If your iRODS server uses short-lived PAM tokens (which is common), the plugin will automatically read your negotiated token from `$HOME/.irods/.irodsA`.

**IMPORTANT**: Because these PAM tokens typically expire quickly (often after 1 hour), you must re-authenticate with the server by running `iinit` before launching your pipeline. To prevent the token from expiring during a long-running pipeline execution, it is highly recommended to request a longer token lifetime using the `--ttl` option (in hours):
```bash
iinit --ttl 120
nextflow run your_pipeline.nf
```
If your token has expired during a long-running execution or between runs, you will receive an `iRODS Authentication Failed! Your short-lived PAM token has likely EXPIRED` error (iRODS error `-840000`) in the Nextflow log, and you will need to run `iinit` again with a longer TTL.

## How to Use

Once configured, Nextflow can resolve iRODS paths natively in pipeline channels and parameters. 

> [!WARNING]
> **Globbing Limitation**: Wildcard pattern matching (using `*`) is not supported natively via the `irods://` protocol in this plugin. Instead of glob patterns, you must pass multiple files explicitly as a list of absolute paths.

Here is an example of passing multiple paths to a channel and running a process:

```nextflow
params.reads = [
    'irods:///zone/home/user/data/sample1_1.fastq.gz',
    'irods:///zone/home/user/data/sample1_2.fastq.gz'
]

process FASTQC {
    input:
    path read

    output:
    path "fastqc_*.html"

    script:
    """
    fastqc ${read}
    """
}

workflow {
    Channel.fromList(params.reads)
        .map { file(it) }
        .set { reads_ch }

    FASTQC(reads_ch)
}
```
