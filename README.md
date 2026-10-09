# WorldEditMinestom

WorldEdit for [Minestom](https://minestom.net). Runs `worldedit-core` on a Minestom server, so you get
`//set`, `//replace`, `//sphere`, brushes, masks, patterns etc.

We use it on the build servers of [IONNetwork](https://ion-network.de). It is not a standalone plugin:
it loads as an extension into our Minestom server and asks the server's edit API (`EditGuard` /
`EditTarget`) which map a player may edit and whether a change is allowed. So if you want to use it
somewhere else you will have to provide that API yourself (see below).

## What's missing

Some commands are turned off on purpose because the server already has its own version of them:

- `//wand`, `//sel`, `//desel` - selection comes from the server's wand
- `//undo`, `//redo` - the server keeps one history for everything
- `//schematic` - the server has its own schematic library
- `//superpickaxe` - it bypasses the edit checks
- `//snapshot`, `//chunkinfo`, `//cs`

## Building

You need JDK 25.

```bash
./gradlew build    # build/libs/WorldEditMinestom.jar
./gradlew test
```

The build expects `de.ionnetwork:minestom-extension-api` in your local Maven repo. That's the
small extension API from our server and isn't published anywhere yet, so for now this only builds if
you have it (or write your own with the same interfaces).

## License

GPLv3, same as WorldEdit. See `LICENSE`.
