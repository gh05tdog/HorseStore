# HorseStore

HorseStore is a Minecraft plugin for Paper servers that allows players to store and manage their horses. Players can save their horses in numbered slots and retrieve them later, preserving all horse attributes including inventory, stats, and appearance.

## Features

- Store horses in numbered slots for later retrieval
- Preserve all horse attributes:
  - Speed and jump strength
  - Color and style
  - Custom name
  - Inventory (saddle, armor)
- Automatic horse storage when:
  - Players log out
  - Players move too far away from their horses
  - Chunks with horses unload
- User-friendly command system with tab completion
- GUI menu for horse management (accessible via command)

## Requirements

- Paper server (version 1.21.x)
- Java 21 or higher

## Installation

1. Download the latest release JAR file from the [release page](https://github.com/gh05tdog/HorseStore/releases)
2. Place the JAR file in your server's `plugins` folder
3. Restart your server or use a plugin manager to load the plugin
4. The plugin will create necessary configuration files on the first run

## Usage

### Commands

- `/horse spawn <slot>` - Spawns a horse from the specified slot
- `/horse store <slot>` - Stores the horse you're riding in the specified slot
- `/horse kill` - Removes the horse you're riding (without storing it)
- `/horse list` - Lists all your stored horses

### Permissions

- `horse.use` - Allows use of all horse commands

## Configuration

The plugin stores horse data in a database file. No additional configuration is required for basic functionality.

## Building from Source

### Prerequisites

- Java Development Kit (JDK) 21
- Maven

### Build Steps

1. Clone the repository
   ```
   git clone https://github.com/gh05tdog/HorseStore.git
   ```

2. Navigate to the project directory
   ```
   cd HorseStore
   ```

3. Build with Maven
   ```
   mvn clean package
   ```

4. The compiled JAR will be in the `target` directory


## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

If you encounter any issues or have questions, please open an issue on the GitHub repository.