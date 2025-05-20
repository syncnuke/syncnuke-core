
# Playback Data Types

This is a reference sheet for the different playback data types used in the project, as different video players and sync protocols keep track of progress in different ways.

| System                                   | Wire-type                            | Example                        | Fractional?                  | Practical resolution     |
| ---------------------------------------- | ------------------------------------ |--------------------------------|------------------------------| ------------------------ |
| **VLC HTTP** (`/requests/status.json`)   | 32-bit signed integer                | `"time": 187`                  | **No** – whole seconds only  | 1 s                      |
| **MPV IPC** (`time-pos`)                 | 64-bit IEEE-754 float                | `"data": 187.532`              | **Yes** – fractional seconds | ≈1 ms (double precision) |
| **Syncplay JSON** (`playstate.position`) | 64-bit IEEE-754 float                | `"position": 187.532914`       | **Yes** – fractional seconds | ≈1 ms (double precision) |
| **DataSaver TCP**                        | 64-bit IEEE-754 float                | `0X4067710624DD2F1B` → 187.532 | **Yes** – fractional seconds | ≈1 ms (double precision) |

### Notes

- All systems measure **“seconds from the start of the item”**.
- The primary difference between these systems is whether they include fractional seconds in their representation.
