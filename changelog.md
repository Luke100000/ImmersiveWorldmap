# TODO

* Maintains an SQLite database of (x, y, z, dimension, lod, colors) for each chunk.
* Convert chunks into colors (bytes)
* Intercept client sided chunks
* Generate LODs on demand
* Generate efficient meshes on demand
* Render in map gui
    * Which disables level renderer to fully focus on rendering the map
* Expose an API (Java and Web)

Difference to Voxy? Significantly smaller overhead, focus on providing vanilla compatible voxel data.
Tho why not just depend on Voxy?


I could make a proof of concept in python which loads nbt map data and visualizes it using three or something.