workflow {
    def input_path = file("irods:///tempZone/home/rods/test_input.txt")
    log.info "Writing test data to iRODS..."
    input_path.text = "Hello from Nextflow iRODS!"

    log.info "Reading test data from iRODS..."
    def content = input_path.text
    log.info "Read content: ${content}"
    if (content == "Hello from Nextflow iRODS!") {
        log.info "Success! iRODS file operations are working natively via Nextflow iRODS plugin."
    } else {
        error "Content mismatch! Expected 'Hello from Nextflow iRODS!' but got '${content}'"
    }
}
