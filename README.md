# Raspberry Pi temperature API

Java 25 / Spring Boot REST API for a **DS18B20** sensor on GPIO4.
Runs in Docker on **64-bit Raspberry Pi OS (ARM64)** using the Linux 1-Wire driver.

## Wire the sensor

Power off the Pi and check your sensor's datasheet for its lead order.

| DS18B20 | Raspberry Pi |
| --- | --- |
| VDD | 3.3 V — physical pin 1 |
| GND | Ground — physical pin 6 |
| Data | GPIO4 — physical pin 7 |

Connect a **4.7 kΩ pull-up resistor** between data and 3.3 V.
Some sensor modules already include this resistor.

## Prepare the Pi

Install Docker Engine with the Compose plugin, enable SSH, and ensure your SSH
user can run Docker without `sudo`.

Add this under `[all]` in `/boot/firmware/config.txt`
(`/boot/config.txt` on older Raspberry Pi OS):

```ini
dtoverlay=w1-gpio,gpiopin=4
```

Reboot, then check the sensor:

```sh
cat /sys/bus/w1/devices/28-*/w1_slave
```

The first line should end in `YES`; the second should contain `t=...`.
If no sensor appears, check the wiring, resistor and 1-Wire configuration.

## Deploy

Your computer needs Docker running, Buildx, the Compose plugin, Bash, SSH and gzip.
The Pi needs gzip too. Run from this repository, replacing the SSH username and host:

```sh
./scripts/deploy-to-pi.sh pi@raspberrypi.local
```

The script builds and tests the ARM64 image, transfers it over
SSH, and runs Compose on the Pi.
Container settings live in [compose.yaml](compose.yaml).

Optional settings:

| Variable | Default |
| --- | --- |
| `SENSOR_ID` | Auto-select a single DS18B20 |
| `API_PORT` | `8080` |
| `SSH_PORT` | `22` |
| `IMAGE_TAG` | Generated deployment tag |
| `SENSOR_SAMPLE_DELAY_MS` | `1000` — delay after each read |
| `SENSOR_MAX_AGE` | `5s` — maximum valid sample age |

```sh
SENSOR_ID=28-000000000001 API_PORT=8081 ./scripts/deploy-to-pi.sh pi@raspberrypi.local
```

Set `SENSOR_ID` when multiple sensors are connected. Pass options as environment
variables; the script does not load your local `.env` file.

Re-run the script to deploy changes. Compose configuration is saved to
`~/gpio-temp-raspi-api/compose.yaml` on the Pi. Updates briefly interrupt the
service and have no automatic rollback. Stop any older container using the same
HTTP port before deploying.

## Use the API

Wait a few seconds after deployment, then request the temperature:

```sh
curl http://raspberrypi.local:8080/api/temperature
```

```json
{
  "sensorId": "28-000000000001",
  "temperatureCelsius": 23.125,
  "measuredAt": "2026-10-11T10:30:00Z"
}
```

The sensor is sampled in the background, with a one-second delay after each
read completes. Requests immediately return the latest valid sample;
`measuredAt` is its measurement completion time in UTC. Sensor conversion time
adds to the interval between samples.

Until the first valid sample, or when the last valid sample is older than five
seconds, the API returns **HTTP 503**. Failed reads retain the last valid sample
until it expires; sampling keeps retrying and recovers automatically.
The API has no authentication; use it on a trusted network.

View logs:

```sh
ssh pi@raspberrypi.local 'cd ~/gpio-temp-raspi-api && docker compose logs temperature-api'
```

## Local development

Install Java 25 and Maven 3.6.3 or newer:

```sh
mvn verify
mvn spring-boot:run
```

Tests require no Pi. To run locally with sample sensor data, create a fixture and
start the app with its directory:

```sh
mkdir -p /tmp/w1-demo/28-000000000001
printf 'aa : crc=aa YES\naa t=23125\n' > /tmp/w1-demo/28-000000000001/w1_slave
SENSOR_BASE_PATH=/tmp/w1-demo mvn spring-boot:run
```

Then call `http://localhost:8080/api/temperature` from another terminal.
This fixture returns static sample data; the default sensor directory is
`/sys/bus/w1/devices`.
