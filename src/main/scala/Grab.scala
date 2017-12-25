import org.json4s._ //Json parser
import org.json4s.native.JsonMethods._ //Json parser

import scala.util._ //Try, success, failure, etc
import scala.io.Source //Read files, local and remote
import java.time.LocalDate //DateTime ops
import java.time.format.DateTimeFormatter //Format datetime

import sys.process._ //Piping
import java.net.URL //Donwload
import java.io.File // Save
import java.nio.file._ // Create hard link
import scala.language.postfixOps //Allow '!!' and '!' at end of pipe call

object Grab { 
    /* 
     * TODO:
     * Deal with API limits
     * Detect non-zero exit codes, and cleaup up files that were created. For example
     *   if `youtube-dl` fails in the middle of a download, temporary files are created.
     */
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateRegex = """(\d\d\d\d)-(\d\d)-(\d\d)""".r

    def main(args: Array[String]) = {
        val options = parseArgs(args)
        
        options('mode).asInstanceOf[String] match{
            case "catch-up" => catchupmode()
            case "normal" => normalmode(
                options('start).asInstanceOf[LocalDate],
                options('end).asInstanceOf[LocalDate]
            )
            case err => error("Invalid mode: %s".format(err))
        }
    }

    def usage() = {
        println("""
USAGE: 
  Using sbt:
    In the sbt console, execute 'run [PARAMS]'

  Using standalone
    Compile the standalon application with 'sbt assembly', then execute with
    'scala target/scala-2.12/NASA\ APOD\ grabber-assembly-1.0.jar PARAMS'

PARAMS: 
  -m, --mode [MODE]     Specify mode of operation. Valid options are:
                        'normal'    : Download all media between start 
                                      and end date (inclusive). This is
                                      the default mode of operation.
                        'catch-up'  : Use the last succesfully downloaded 
                                      media date as a start date. This is
                                      useful if the script is run regularly,
                                      say everyday via a cron job. If days
                                      are missed, the script will simply 
                                      "pick up where it left off last".
                                      Start and end dates are ignored in
                                      this mode.
  -s, --start [DATE]    Specify start date in yyyy-mm-dd form (default to today)
  -e, --end [DATE]      Specify end date in yyyy-mm-dd form (default to today)
  -h, --help            Display usage and help text

  See README.md for more details
""")
        System.exit(0)
    }

    def lastCompleteDate(): LocalDate = {
        val dest_dir = new java.io.File(Config.pod_dir)
        // Make sure directory in config is present
        if(!dest_dir.exists){
            error("Invalid pod_dir: %s".format(Config.pod_dir))
        }

        val sortedListOfFiles = dest_dir.listFiles.filter{
                s => dateRegex.findFirstIn(s.getName).isDefined
            }.toList.sorted
        if (sortedListOfFiles.isEmpty) {
            println("[-] There are no previous donwloads found in the pod_dir! Using today to start")
            LocalDate.now
        }
        else{
            val lastFile = sortedListOfFiles.last
            val lastDate: Option[String] = dateRegex.findFirstIn(lastFile.getName)
            val tryParse = for {
                date <- lastDate
                parsed <- parseDate(date).toOption
            } yield parsed.plusDays(1)

            // Return either the succesfully parsed new start date, or today if parse failed
            tryParse getOrElse LocalDate.now
        }
    }

    def catchupmode() = {
        val lastcomplete = lastCompleteDate
        val endDate = LocalDate.now

        if(lastcomplete.isAfter(endDate)){
            println("[*] No new media to catch up on, we are up to date.\n")
        }
        else{
            normalmode(lastcomplete,endDate)
        }
    }

    def normalmode(startDate: LocalDate, endDate: LocalDate) = {
        if(startDate.isAfter(endDate)){
            println("[!] Start date is after end date!.")
        }
        else{
            printf("[*] Grabbing NASA APOD from %s -> %s\n",startDate.format(fmt),
                endDate.format(fmt))

            // For every date from start to end inclusive...
            val numberOfDays = java.time.temporal.
                ChronoUnit.DAYS.between(startDate,endDate).toInt
            for (diff <- 0 to numberOfDays){
                // Grab the file if we don't already have it
                MediaGrabber.CheckAndGrab(startDate.plusDays(diff))
            }
            println("[+] No more dates, done\n")
        }
    }

    def parseDate(datein: String): Try[LocalDate] = {
        Try(LocalDate.parse(datein,fmt))
    }

    def parseArgs(args: Array[String]): Map[Symbol,Any] = {
        val arglist = args.toList

        def nextOption(map : Map[Symbol,Any], list: List[String]) : Map[Symbol,Any] = {
            list match {
                case Nil => map
                case ("-m" | "--mode") :: value :: tail =>
                    nextOption(map ++ Map('mode -> value), tail)

                case ("-s" | "--start") :: value :: tail =>
                    parseDate(value) match {
                        case Success(startDate) => nextOption(map ++ Map('start -> startDate), tail)
                        case Failure(ex) => error("Invalid start date input (%s). Must be yyyy-mm-dd.".format(value)); map
                    }
                case ("-e" | "--end") :: value :: tail =>
                    parseDate(value) match {
                        case Success(endDate) => nextOption(map ++ Map('end -> endDate), tail)
                        case Failure(ex) => error("Invalid end date input (%s). Must be yyyy-mm-dd.".format(value)); map
                    }
                case ("-h" | "--help") :: other => {
                    usage
                    map
                }
                case option :: tail => {
                    println("[!] Unknown option "+option) 
                    map
                }
            }
        }
        // Start tail recursion with defaults already in place
        nextOption(Map(
                ('mode -> "normal"),
                ('start -> LocalDate.now),
                ('end -> LocalDate.now),
            ),arglist)
    }

    def error(error_msg: String) = {
        System.err.println("[!] "+error_msg)
        System.exit(1)
    }
}

object Config {
    var config : Config_Opts = null
    val config_path = "./config.json"
    
    /* Case class used to parse option config file
     * These names match the fields exactly in config */
    case class Config_Opts (
        pod_dir : String = "",
        screensaver_dir : String = "",
        api_key : String = "")

    def pod_dir() : String = {
        read_config
        // If it is still empty...
        if (config.pod_dir=="") Grab.error("No POD directory set in config file")

        config.pod_dir
    }
    def screensaver_dir() : String = {
        read_config

        config.screensaver_dir
    }
    def api_key() : String = {
        read_config
        // If it is still empty...
        if (config.api_key=="") Grab.error("No API key found in config file")

        config.api_key
    }

    def read_config() = {
        if (config == null){
            // Read file
            val jsonTree = parse(Source.fromFile(config_path).mkString)
            //Get Case class
            implicit val formats = DefaultFormats
            config = jsonTree.extract[Config_Opts]
        }
    }
}


object MediaGrabber{
    /* Case class for parsing the JSON response
     * sent by the NASA pod API. These field names
     * match exactly those sent by the API. */
    case class Response(
        copyright : String = "",
        date : String = "",
        explanation : String = "",
        hdurl : String = "",
        media_type : String = "",
        service_version : String = "",
        title : String = "",
        url : String = "" )

    /* Check if we already downloaded the POD for
     * this date. If not, download it */
    def CheckAndGrab(date: LocalDate) : Boolean = {
        val date_str = date.format(Grab.fmt)
        val dest_dir = new java.io.File(Config.pod_dir)

        // Make sure directory in config is present
        if(!dest_dir.exists){
            Grab.error("Invalid pod_dir: %s".format(Config.pod_dir))
        }

        // Check if file is present in pod_dir
        val matches = dest_dir.listFiles.filter(_.isFile).toList.filter{ file =>
            file.getName.startsWith(date_str)
        }

        // If it is, grab image for this date
        if(matches.length==0){
            GrabMedia(date_str)
        }
        else{
            // Otherwise, continue
            printf("[-] File already exists for date: %s, continuing on next date\n",
                date_str)
            false
        }

    }

    /* Make the API call and parse the JSON, returning an
     * instance of the case class Response, in the form of
     * an option */
    def FetchJson(date : String = "") : Option[Response] = {
        val API_KEY = Config.api_key
        val URL = "https://api.nasa.gov/planetary/apod" ++
                  "?api_key=%s".format(API_KEY) ++
                  (if (date != "") "&date=%s".format(date) else "") ++
                  "&hd=True"
        try{
            val json = Source.fromURL(URL)
            val jsonTree = parse(json.mkString)

            implicit val formats = DefaultFormats
            Some(jsonTree.extract[Response])
        }
        catch{
            case io : java.io.IOException  => 
                printf("[-] Server returned bad HTTP response code:\n\t%s\n",io)
                None
            case e  : Throwable => 
                e.printStackTrace
                None
        }

    }

    /* Call Fetch Json and match to correct media downloader */
    def GrabMedia(date : String = ""): Boolean = {
        printf("[+] Grabbing for day: %s\n",date)
        FetchJson(date) match {
            case Some(resp) => {
                printf("[+]\tDate: %s\n\tTitle: %s\n\tType: %s\n",resp.date,
                    resp.title,resp.media_type)

                resp.media_type match {
                    case "image" => GrabImage(resp)
                    case "video" => GrabVideo(resp)
                    case default => {
                      printf("[!] Unknown media type found: %s\n",default)
                    }
                }
                printf("[+] Done with %s\n",resp.date)
                true
            }
            case None  => {
                printf("[-] Could not query NASA API. Make sure you are connected to the internet and you have a valid api_key in the config.json file\n")
                false
            }
        }
    }
    
    /* Helper method for creating the destination filename 
     * from the API response */
    def MakeFilename(resp: Response): String = {
        resp.date+"-"+resp.title.replaceAll("[^A-Za-z0-9]","_")
    }

    /* Grab a POD where media_type==image 
     * Additionally, create a hardlink in the screensaver_dir
     * if the config is set to do so. */
    def GrabImage(resp: Response): Boolean = {
        val url = if (resp.hdurl!="") resp.hdurl else resp.url
        val file_ext = url.split('.').last
        val out_fn = MakeFilename(resp)+"."+file_ext
        val out_full = Config.pod_dir+out_fn
        printf("[+] Grabbing image from URL: %s\n",resp.hdurl)
        printf("[+] Saving as filename: %s\n", out_full)

        // Download image and save
        val exit_status = new URL(resp.hdurl) #> new File(out_full) !

        if (exit_status != 0){
            printf("[-] Download and save returned with exit status: %d\n",
                exit_status)
            false
        }
        else{
            // Link to screen saver path
            if (Config.screensaver_dir != ""){
                try{
                    val destination = Config.screensaver_dir+out_fn
                    Files.createLink(Paths.get(destination), Paths.get(out_full));
                }
                catch{
                    case exists : java.nio.file.FileAlreadyExistsException => {
                        printf("[-] Hard link already exists\n")
                    }
                    case e : Throwable => {
                        printf("[!] Error creating link!")
                        e.printStackTrace
                    }
                }
            }
            true
        }
    }

    /* Grab a POD where media_type==video */
    def GrabVideo(resp: Response): Boolean = {
        val out_fn = MakeFilename(resp)
        val out_full = Config.pod_dir+out_fn

        printf("[+] Grabbing video from URL: %s\n",resp.url)
        printf("[+] Saving as filename: %s\n", out_full)

        /* This call attempts to use 'youtube-dl' to download
         * the video media at the url. It tries to download the 
         * highest quality mp4 less than 100MB, and falls back to 
         * highest quality anything, less than 100MB */
        val exit_status = "youtube-dl --no-progress "+
            "-f bestvideo[ext=mp4][filesize<100M]+bestaudio[ext=m4a]/"+
                "best[ext=mp4][filesize<100M]/"+
                "best[filesize<100M] "+
            "-o "+out_full+".%(ext)s"+
            " --restrict-filenames "+resp.url !

        if (exit_status != 0){
            printf("[-] Youtube-dl failed with exit status: %d\n",exit_status)
            printf("[-] Skipping this day because there is no easy way to download media\n")
        }
        true

    }
}

