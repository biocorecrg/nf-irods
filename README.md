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

In your `nextflow.config`, add the plugin and specify the connection details for your iRODS server:

```nextflow
plugins {
    id 'nf-irods'
}

irods {
    host = 'irods.example.com'
    port = 1247
    username = 'your_username'
    password = 'your_password'
    zone = 'zoneName'
    defaultStorageResource = 'demoResc'
}
```

The plugin also supports standard environment variables if credentials are not specified in the configuration:
- `IRODS_HOST`
- `IRODS_PORT`
- `IRODS_USER_NAME`
- `IRODS_PASSWORD`
- `IRODS_ZONE_NAME`
- `IRODS_DEFAULT_RESOURCE`

## How to Use

Once configured, Nextflow can resolve iRODS paths natively in pipeline channels and parameters:

```nextflow
params.reads = 'irods:///zone/home/user/data/*.fastq.gz'

process FASTQC {
    input:
    path reads

    output:
    path "fastqc_*.html"

    script:
    """
    fastqc ${reads}
    """
}

workflow {
    Channel.fromPath(params.reads) | FASTQC
}
```
