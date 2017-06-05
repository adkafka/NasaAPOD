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
     * Default params...
         * Or, start date is last file in directory... may introduce errors
         * Or, if one date given, only do that date...
     * Read through and refactor some things
         * Use Option when available
         * What to return from funcs...
     */
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    def main(args: Array[String]) = {
        // Grab start and end dates from agrs
        val (startDate, endDate) = parseArgs(args)
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

    def parseArgs(args: Array[String]) = {
        /* For now, default to current date. */
        val startDate = if (args.length>=1) LocalDate.parse(args(0),fmt) 
                        else LocalDate.now
        val endDate   = if (args.length>=2) LocalDate.parse(args(1),fmt) 
                        else LocalDate.now
        if (endDate.isBefore(startDate))
            error("Error, you must provide an end date that is after the start Date")

        (startDate,endDate)
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
    def CheckAndGrab(date: LocalDate) = {
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
    def GrabMedia(date : String = "") = {
        printf("[+] Grabbing for day: %s\n",date)
        FetchJson(date) match {
            case Some(resp) => {
                printf("[+]\tDate: %s\n\tTitle: %s\n\tType: %s\n",resp.date,
                    resp.title,resp.media_type)

                resp.media_type match {
                    case "image" => GrabImage(resp)
                    case "video" => GrabVideo(resp)
                    case default => printf("[!] Unknown media type found: %s\n",default)
                }
                printf("[+] Done with %s\n",resp.date)
            }
            case None  => {
                printf("[-] Could not query NASA API. Make sure you are connected to the internet and you have a valid api_key in the config.json file\n")
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
    def GrabImage(resp: Response) = {
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
        }

        // Link to screen saver path
        if (Config.screensaver_dir != ""){
            try{
                val destination = Config.screensaver_dir+out_fn
                Files.createLink(Paths.get(destination), Paths.get(out_full));
            }catch{
                case exists : java.nio.file.FileAlreadyExistsException => {
                    printf("[-] Hard link already exists\n")
                }
                case e : Throwable => {
                    printf("[!] Error creating link!")
                    e.printStackTrace
                }
            }
        }
    }

    /* Grab a POD where media_type==video */
    def GrabVideo(resp: Response) = {
        val out_fn = MakeFilename(resp)
        val out_full = Config.pod_dir+out_fn

        printf("[+] Grabbing video from URL: %s\n",resp.url)
        printf("[+] Saving as filename: %s\n", out_full)

        /* This call attempts to use 'youtube-dl' to download
         * the video media at the url. It tries to download the 
         * highest quality mp4 less than 100MB, and falls back to 
         * highest quality anything, less than 100MB */
        val exit_status = "youtube-dl --no-progress "+
            "-f bestvideo[ext=mp4][filesize<100M]+bestaudio[ext=m4a]/best[ext=mp4][filesize<100M]/best[filesize<100M] "+
            "-o "+out_full+".%(ext)s"+
            " --restrict-filenames "+resp.url !

        if (exit_status != 0){
            printf("[-] Youtube-dl failed with exit status: %d\n",exit_status)
            printf("[-] Skipping this day because there is no easy way to download media\n")
        }

    }
}

