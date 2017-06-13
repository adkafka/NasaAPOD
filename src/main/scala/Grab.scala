import org.json4s._ //Json parser
import org.json4s.native.JsonMethods._ //Json parser

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
     * TODO...
     * Add more params
        * -s startdate [yyyy-mm-dd]
        * -e enddate [yyyy-mm-dd]
        * -p : prompt to create ignore files on failure
        * -q : quiet?
        * -r : reverse?
     * OR
        * --resume-mode : Start date at last succesful date (last file in pod_dir)
     */
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    def main(args: Array[String]) = {
        val options = parseArgs(args)
        
        options('mode).asInstanceOf[String] match{
            case "resume" => normalmode(
                lastCompleteDate.plusDays(1),
                LocalDate.now
            )
            case "normal" => normalmode(
                options('start).asInstanceOf[LocalDate],
                options('end).asInstanceOf[LocalDate]
            )
            case err => error("Invalid mode: %s".format(err))
        }
    }

    def lastCompleteDate(): LocalDate = {
        val dateRegex = """(\d\d\d\d)-(\d\d)-(\d\d)""".r
        val dest_dir = new java.io.File(Config.pod_dir)
        // Make sure directory in config is present
        if(!dest_dir.exists){
            error("Invalid pod_dir: %s".format(Config.pod_dir))
        }

        val listOfFiles = dest_dir.listFiles.filter{
                s => dateRegex.findFirstIn(s.getName).isDefined
            }.toList
        val lastFile = listOfFiles.sorted.last
        val lastDate = dateRegex.findFirstIn(lastFile.getName)

        lastDate match{
            case Some(datestr) => parseDate(datestr)
            case None => LocalDate.now
        }
    }

    def normalmode(startDate: LocalDate, endDate: LocalDate) = {
        if(startDate.isAfter(endDate)){
            println("[*] Already up to date.")
        }
        else{
            printf("[*] Grabbing NASA POD from %s -> %s\n",startDate.format(fmt),
                endDate.format(fmt))

            // For every date from start to end inclusive...
            val numberOfDays = java.time.temporal.
                ChronoUnit.DAYS.between(startDate,endDate).toInt
            for (diff <- 0 to numberOfDays){
                // Grab the file if we don't already have it
                MediaGrabber.CheckAndGrab(startDate.plusDays(diff))
            }
            println("[+] No more dates, done")
        }
    }

    def parseDate(datein: String): LocalDate = {
        try{
            LocalDate.parse(datein,fmt)
        }
        catch{
            case parseEx : java.time.format.DateTimeParseException => 
                error("Invalid date input (%s). Must be yyyy-mm-dd.".format(datein))
                // Keep compiler happy (unreachable code)
                LocalDate.now
            case e  : Throwable => 
                e.printStackTrace
                // Keep compiler happy (unreachable code)
                LocalDate.now
        }
    }

    def parseArgs(args: Array[String]): Map[Symbol,Any] = {
        val arglist = args.toList

        def nextOption(map : Map[Symbol,Any], list: List[String]) : Map[Symbol,Any] = {
            list match {
                case Nil => map
                case ("-m" | "--mode") :: value :: tail =>
                    nextOption(map ++ Map('mode -> value), tail)

                case ("-s" | "--start") :: value :: tail =>
                    nextOption(map ++ Map('start -> parseDate(value)), tail)
                case ("-e" | "--end") :: value :: tail =>
                    nextOption(map ++ Map('end -> parseDate(value)), tail)

                case option :: tail => {
                    println("Unknown option "+option) 
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
        var URL = "https://api.nasa.gov/planetary/apod"
        URL = URL.concat("?api_key=%s".format(API_KEY))
        if (date != ""){
            URL = URL.concat("&date=%s".format(date))
        }
        URL = URL.concat("&hd=True")
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

