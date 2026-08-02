# TODO

* Distance metric and camera position are fucked
* No spam (if the main iterator slips, it will load the entire world)
* Chunk culling not on sub chunks yet
* No batching at all
* No layering (Flood fill from current layer)
* Caches are all a bit funky and too small
* Threading seems IO locked af
* Upsert doesnt clear parents

## Plan

* Maintains an SQLite database of (x, y, z, dimension, lod, colors) for each chunk.
* Convert chunks into colors (bytes)
* Intercept client sided chunks
* Generate LODs on demand
* Generate efficient meshes on demand
* Render in map gui
    * Which disables level renderer to fully focus on rendering the map
* Expose an API (Java and Web)



Render sky and block light to make it "fair"
Render uniform lod with max range