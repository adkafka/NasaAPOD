# NASA Astronomy Picture of the Day Grabber, in Scala
Written by Adam Kafka

## Introduction
Every day, NASA updates the [Astronomy Picture of the Day (APOD)](https://apod.nasa.gov/apod/), with a new beautiful image or video. This program provides a simple command line interface to download media within a date range from their site. The script uses the [NASA API](https://api.nasa.gov/) to query the service. In order to use the script, you need an API key which you can obtain for free by filling out [the form on their website](https://api.nasa.gov/index.html#apply-for-an-api-key).

Indiviuals can configure the script by creating a 'config.json' file. A skeleton configuration is included as 'config.json.default'. Users should execute ``cp config.json.default config json``, then edit 'config.json' in the text editor of their choice. The default file includes descriptions for the fields.

### Features
- Download both images and video featured on the APOD site
- Support batch downloads by the use of date ranges as command line arguments
- Detect already downloaded media and skip that date
- Support for usage in a task scheduler such as cron
- Easily customizable options in the 'config.json' file
- Can create a hard link in a seperate directory (screensaver\_dir)
    - Useful if you have one directory used a source for a screensaver, desktop background, etc.

## Dependencies
Scala 2.12.2 and sbt 0.13.15 were used during development. More recent versions of these software should work as well. Please submit an issue if you face one.

This script has only been tested on a macOS computer, but it should work on any unix machine, so try it and let me know how it works!

To download videos as well, the python CLI tool [youtube-dl](https://rg3.github.io/youtube-dl/) is needed.

## Usage
### Using sbt
To begin, run ``sbt`` in the cloned directory. From here, we should be able to test the script and its configuration by simply typing ``run`` and pressing enter. This is a good first step to confirm that the program is setup succesfully. Once this is confirmed, you can experiment with date ranges, by executing statements of the form ``run [start-yyyy-mm-dd] [end-yyyy-mm-dd]``, with both parameters defaulting to the value of 'today'. Note that these ranges are inclusive, meaning the end date is downloaded as well. So for example, ``run 2016-01-01 2016-12-31`` will download all APOD media in the year 2016. Similarly, ``run 2017-02-01`` will download all media from February 1st, 2017 up to and including today.

### Standalone
If it is desired to execute the script outside of the sbt tool, one may do so by compiling a complete jar. The easiest way to do this is with the sbt [assembly](https://github.com/sbt/sbt-assembly) plugin. One should be able to simply execute ``sbt assembly`` or ``assembly`` in an sbt shell. If you run into problems, see their site and let me know so I can update the code and documentation. Upon success, a jar in the form of ``target/scala-2.12/NASA APOD grabber-assembly-1.0.jar`` will be produced. This jar can be executed indepently of sbt by running ``scala target/scala-2.12/NASA\ APOD\ grabber-assembly-1.0.jar`` from within the project root. Parameters can still be passed in using the same form as previously outlined.

## API Rate Limits
As of now, the NASA API has a rate limit of 'Hourly Limit: 1,000 requests per hour' ([See their documentation for more details](https://api.nasa.gov/api.html#web-service-rate-limits)). If you exceed this rate, the script will start to fail, returning an HTTP error code. If this happens, simply wait one hour then re-execute the command. It will pick up where it left off.

## Cron
The daily nature of the task sets it up as a perfect fit for a cron job. The example below demonstrates a cron task (created using ``crontab -e``) to check hourly between 10am and 9pm, everyday. The example below uses the standalone version compiled with ``sbt assembly`` above. The script will only download the media once per day, because it checks the destination directory (pod\_dir) for the media before querying the NASA API.

```
SHELL=/bin/bash #If you use a different shell, change it here
PATH=/usr/local/bin:/usr/local/sbin:... #Make sure 'scala' and 'youtube-dl' are on the path

# Min   Hour    Day Month   DayWeek   Command
# (0-59)  (0-23)     (1-31)    (1-12 or Jan-Dec)  (0-6 or Sun-Sat)

# Nasa Pic of Day
0      10-21      *       *       *       cd /PATH/TO/REPO; scala target/scala-2.12/NASA\ APOD\ grabber-assembly-1.0.jar >> /PATH/TO/LOG  2>&1
```

## Scala Patterns Used
This project was created as an introduction into the Scala programming langauge. Therefore, it is a good example of some of the simple mechanisms that Scala provides to its users.

#### Option
Options are Scala's cleaner way of dealing with failure in functions that are expected to return an object upon success. For example, MediaGrabber.FetchJson returns Option[Response]. Subsequently, MediaGrabber.GrabMedia uses match in the calling code to deal with successs and failure cleanly.

#### System Commands
For more information, see the [blogpost on Alvin Alexander's site](http://alvinalexander.com/scala/scala-execute-exec-external-system-commands-in-scala). This is used when downloading the image ``val exit_status = new URL(resp.hdurl) #> new File(out_full) !`` and downloading the video with the python program youtube-dl.

#### Case Classes
Case classes are used to make JSON parsing easy, using the [json4s](https://github.com/json4s/json4s) package. This occurs when querying the NASA API (see Response case class) and when parsing the config file (see class Config\_opts in the Config singleton object).

#### Java Interoperation
Throughout the project, Java code is called alongside Scala. One example of this is date parsing, formatting, and iteration.

#### Singleton Objects
The project is composed of three singleton objects: Grab, Config, MediaGrabber. These objects help seperate the logical tasks of the program and avoid polluting the global namesapce.
