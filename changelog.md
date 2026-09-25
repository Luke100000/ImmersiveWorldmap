# TODO

* Deduplicate incoming chunks (hash? Time?)
* Screen transition
* Make sure the screen does not render background
* Finalize screen
* Markers
* Cave viewer
    * 2x2 median filtered +-8 search cut
* Culling
    * Search downwards for the first solid block, treat this as lower bound
* Fade out
* Debug view
* Profiling and maybe batching, especially the air-chunks
* Is the renderer good or too many CPU bound calls?

# V1

* Optional server support (Fetch chunks from server directly? Make the database common sided?)