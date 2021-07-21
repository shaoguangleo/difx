#define SENDOPTIONS  0

#ifdef __APPLE__
#define OSX
#elif defined(__FreeBSD__)
#define FREEBSD
#elif defined(__linux__)
#define LINUX
#undef SENDOPTIONS
#define SENDOPTIONS MSG_NOSIGNAL
#else
#define GENERIC
#endif

#include <sys/types.h>
#include <time.h>
#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>     
#include <sys/timeb.h>
#include <sys/time.h>       
#include <sys/socket.h>  
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>

#include "vdifio.h"
#include "codifio.h"


#define MAXVDIFTHREADS       64
#define DEFAULT_BUFSIZE    1000  //  KB
#define MAXSTR              200 

#define DEBUG(x) 

typedef struct udp {
  int enabled;
  int size;
  int vtp;
  u_int64_t sequence;
  double usleep;
  double lastsleep;
  struct sockaddr_in dest;
} udp;

double tim(void);
void kill_signal (int);
int setup_net(char *hostname, int port, int window_size, udp *udp, int *sock);
void throttle_rate (double firsttime, float datarate, 
		    unsigned long long totalsize, double *tbehind);
double currentmjd ();
void mjd2cal(double mjd, int *day, int *month, int *year, double *ut);
int cal2dayno(int day, int month, int year);
void dayno2cal(int dayno, int year, int *day, int *month);
double cal2mjd(int day, int month, int year);
int netsend(int sock,  struct msghdr *msg, struct iovec *iovect, udp *udp);
void my_usleep(udp *udp);

typedef enum {VDIF, CODIF} datamode;

int main(int argc, char * const argv[]) {
  int i, status, opt, tmp, sock, thisday, thismonth, thisyear, header_bytes=0;
  int hour, min, sec, bufsize, datarate, currentthread, framepersec;
  int sampleblocklength=0, ihead;
  char msgstr[MAXSTR+50];
  char *buf;
  uint64_t mjdsec, bwrote, totalpackets, totalsamples=0;
  double thismjd, finishmjd, ut, t0, t1, t2, dtmp;
  float speed, ftmp;
  unsigned long long totalsize;
  vdif_header vdif_headers[MAXVDIFTHREADS];
  codif_header codif_headers[MAXVDIFTHREADS];
  struct msghdr msg;
  struct iovec  iovect[3];

  //int monitor = 0;  
  int port = 52100;    /* Write nfiles files of size total size */
  int window_size = -1;	/* kilobytes */
  //int datarate = 512; /* Mbps */
  int year = -1;
  int month = -1;
  int day = -1;
  int dayno = -1;
  double mjd = -1;
  int numchan = 4;
  int numthread = 1;
  int firstthread = 0;
  int drop = -1;
  datamode mode = VDIF;
  int bits=2;
  int bandwidth=16;
  int framesize=9000;
  int complex=0;  /* Use complex sampling, if VDIF */
  float rate = 0; /* Limit read/write to this data rate - UDP only*/
  float updatetime = 1; /* 1 second update by default */
  float filetime = 10; /* 1 second "files" */
  double duration = 60; /* 1min by default */
  char hostname[MAXSTR+1] = ""; /* Host name to send data to */
  char timestr[MAXSTR] = "";

  udp udp;
  udp.size = 0;
  udp.enabled = 0;  
  udp.usleep = 0;  
  udp.lastsleep = 0;
  udp.vtp = 1; // Added 64bit sequece number to UDP data

  struct option options[] = {
    {"duration", 1, 0, 'd'},
    {"bandwidth", 1, 0, 'W'},
    {"day", 1, 0, 'D'},
    {"dayno", 1, 0, 'Y'},
    {"month", 1, 0, 'm'},
    {"mjd", 1, 0, 'M'},
    {"year", 1, 0, 'y'},
    {"year", 1, 0, 'y'},
    {"time", 1, 0, 't'},
    {"port", 1, 0, 'p'},
    {"host", 1, 0, 'H'},
    {"update", 1, 0, 'u'},
    {"window", 1, 0, 'w'},
    {"blocksize", 1, 0, 'S'},
    {"framesize", 1, 0, 'F'},
    {"filetime", 1, 0, 'f'},
    {"udp", 1, 0, 'U'},
    {"rate", 1, 0, 'r'},
    {"sleep", 1, 0, 's'},
    {"usleep", 1, 0, 's'},
    {"nchan", 1, 0, 'n'},
    {"bits", 1, 0, 'b'},
    {"nthread", 1, 0, 'T'},
    {"firstthread", 1, 0, 'Z'},
    {"drop", 1, 0, 'j'},
    {"codif", 0, 0, 'c'},
    {"novtp", 0, 0, 'V'},
    {"complex", 0, 0, 'C'},
    {"help", 0, 0, 'h'},
    {0, 0, 0, 0}
  };

  bufsize  = DEFAULT_BUFSIZE * 1024;
  i = -1;

  setenv("TZ", "", 1); 
  tzset();

  setlinebuf(stdout);

  framepersec = 0;
  
  /* Read command line options */
  while (1) {
    opt = getopt_long_only(argc, argv, "cCr:n:DVvd:mhH:f:b:", 
			   options, NULL);
    if (opt==EOF) break;

    switch (opt) {
      
    case 'S':
      status = sscanf(optarg, "%f", &ftmp);
      if (status!=1)
	fprintf(stderr, "Bad blocksize option %s\n", optarg);
      else 
	bufsize = ftmp * 1024;
     break;

    case 'f':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1 || tmp==0)
	fprintf(stderr, "Bad filesize option %s\n", optarg);
      else 
	filetime = tmp;
     break;

    case 'w':
      status = sscanf(optarg, "%f", &ftmp);
      if (status!=1)
	fprintf(stderr, "Bad window option %s\n", optarg);
      else 
	window_size = ftmp * 1024;
     break;
     
    case 'W':
      status = sscanf(optarg, "%f", &ftmp);
      if (status!=1)
     	fprintf(stderr, "Bad bandwidth option %s\n", optarg);
      else 
     	bandwidth = ftmp;
      break;

    case 'p':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad port option %s\n", optarg);
      else {
	port = tmp;
      }
      break;
      
    case 'd':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad duration option %s\n", optarg);
      else {
	duration = tmp;
	printf("Duration = %.1f\n", duration);
      }
      break;

    case 'F':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
        fprintf(stderr, "Bad framesize option %s\n", optarg);
      else {
        framesize = tmp;
      }
      break;

    case 'D':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad day option %s\n", optarg);
      else {
	day = tmp;
      }
      break;

    case 'Y':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad dayno option %s\n", optarg);
      else {
	dayno = tmp;
      }
      break;

    case 'n':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad nchan option %s\n", optarg);
      else {
	numchan = tmp;
      }
      break;

    case 'b':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad bits option %s\n", optarg);
      else {
	bits = tmp;
      }
      break;

    case 'T':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
        fprintf(stderr, "Bad nthread option %s\n", optarg);
      else {
        numthread = tmp;
      }
      break;

    case 'Z':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
        fprintf(stderr, "Bad nthread option %s\n", optarg);
      else if (tmp<0) {
        fprintf(stderr, "Ignoring negative first thread (%s)\n", optarg);
      } else {
        firstthread = tmp;
      }
      break;

    case 'j':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
        fprintf(stderr, "Bad drop option %s\n", optarg);
      else {
        drop = tmp;
      }
      break;
      
    case 'm':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad month option %s\n", optarg);
      else {
	month = tmp;
      }
      break;

    case 'M':
      status = sscanf(optarg, "%lf", &dtmp);
      if (status!=1)
	fprintf(stderr, "Bad MJD option %s\n", optarg);
      else {
	mjd = dtmp;
      }
      break;
      
    case 'y':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad year option %s\n", optarg);
      else {
	year = tmp;
      }
      break;
      
    case 't':
      strcpy(timestr, optarg);
      break;
      
    case 'u':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad time update option %s\n", optarg);
      else 
	updatetime = tmp;
      break;
      
    case 's':
      status = sscanf(optarg, "%lf", &dtmp);
      if (status!=1)
	fprintf(stderr, "Bad usleep option %s\n", optarg);
      else 
	udp.usleep = dtmp/1e6;
      break;

    case 'r':
      status = sscanf(optarg, "%f", &ftmp);
      if (status!=1)
	fprintf(stderr, "Bad rate option %s\n", optarg);
      else 
	rate = ftmp;
      break;
      
    case 'H':
      if (strlen(optarg)>MAXSTR) {
	fprintf(stderr, "Hostname too long\n");
	return(1);
      }
      strcpy(hostname, optarg);
      break;

    case 'U':
      status = sscanf(optarg, "%d", &tmp);
      if (status!=1)
	fprintf(stderr, "Bad udp option %s\n", optarg);
      else {
	udp.enabled = 1;
	udp.size = tmp;
      }
      break;

    case 'c':
      mode = CODIF;
      break;

    case 'V':
      udp.vtp = 0;
      break;
      
    case 'C':
      complex = 1;
      break;

    case 'h':
      printf("Usage: vlbi_fake [options]\n");
      printf("  -H/host <HOSTNAME>    Remote host to connect to\n");
      printf("  -p/-port <PORT>       Port number for transfer\n");
      printf("  -d/duration <DUR>     Time in (transferred) seconds to run (60)\n");
      printf("  -bandwidth <BANWIDTH> Channel bandwidth in MHz (16)\n");
      printf("  -framesize <FRAMESIZE>(Data) frame size for VDIF data (bytes)\n");
      printf("  -n/-nchan <N>         Number of channels to assume in stream\n");
      printf("  -b/-bits <N>          Number of bits/channel\n");
      printf("  -day <DAY>            Day of month of start time (now)\n");
      printf("  -month <MONTH>        Month of start time (now)\n");
      printf("  -dayno <DAYNO>        Day of year of start time (now)\n");
      printf("  -year <YEAR>          Year of start time (now)\n");
      printf("  -time <HH:MM:SS>      Year of start time (now)\n");
      printf("  -mjd <MJD>            MJD of start time\n");
      printf("  -codif                Send CODIF format data (default VDIF)\n");
      printf("  -udp <MTU>            Use UDP with given datagram size\n");
      printf("  -sleep <USEC>         Sleep (usec) between udp packets\n");
      printf("  -u/-update <SEC>      Number of seconds to average timing statistics\n");
      printf("  -w/-window <SIZE>     TCP window size (kB)\n");
      printf("  -S/blocksize <SIZE>   Blocksize to write, kB (1 MB default)\n");
      printf("  -f/filesize <SIZE>    Size in sec for files (1)\n");
      printf("  -nthread <NUM>        Number of threads (VDIF only)\n");
      printf("  -drop <NUM>           Drop every NUM packets (UDP only)\n");
      printf("  -complex              Complex samples (VDIF only)\n");
      printf("  -novtp                Don't use VTP protocol (raw VLBI data)\n");
      printf("  -h/-help              This list\n");
      return(1);
    break;
    
    case '?':
    default:
      break;
    }
  }

  thismjd = currentmjd();
  mjd2cal(thismjd, &thisday, &thismonth, &thisyear, &ut);

  if (year==-1) year = thisyear;
  if (day==-1) day = thisday;
  if (month==-1) month = thismonth;
  if (dayno!=-1) dayno2cal(dayno, year, &day, &month);

  if (numthread > MAXVDIFTHREADS) {
    fprintf(stderr, "Too many VDIF/CODIF threads %d cf max %d\n", numthread, MAXVDIFTHREADS);
    exit(1);
  }

  if (strlen(timestr)>0) {
    status = sscanf(timestr, "%d:%d:%d", &hour, &min, &sec);
    if (status!=3) {
      sec = 0;
      status = sscanf(timestr, "%d:%d", &hour, &min);
      if (status!=2) {
	hour = min = 0;
	status = sscanf(timestr, "%d", &sec);
	if (status!=1) {
	  fprintf(stderr, "Could not parse time %s\n", timestr);
	  exit(1);
	}
      }
    }
    ut = (hour+(min+sec/60.0)/60.0)/24.0;
  }

  if (mjd<0) 
    mjd = cal2mjd(day, month, year)+ut;
  else 
    mjd = mjd+ut;

  finishmjd = mjd+duration/(60.*60.*24.);

  printf("Using MJD=%.4f\n", mjd);

  
  datarate = numchan*bits*bandwidth*2; // Mbps (per thread if nthread>1), assuming Nyquist
  printf("Datarate is %d Mbps\n", datarate*numthread);

  if (udp.enabled) {
    udp.sequence = 0;
    udp.size -= 20; // IP header
    udp.size -= 4*2; // UDP header
    if (udp.vtp) udp.size -= sizeof(long long); // Sequence number
    udp.size &= ~0x7;  // Truncate to smallest multiple of 8
   
    if (udp.size<=0) {
      printf("Error: Specified UDP MTU size (%d) too small\n", 
	     udp.size+20+4*2+(int)sizeof(long long));
      exit(1);
    }
  } else {
    drop = -1;
  }

  if (mode==VDIF) 
    header_bytes = VDIF_HEADER_BYTES;
  else {
    header_bytes = CODIF_HEADER_BYTES;
    sampleblocklength = numchan*bits;
    if (complex) sampleblocklength *= 2;
    sampleblocklength /= 8;
    if (sampleblocklength==0) sampleblocklength=1;
    totalsamples = bandwidth*1e6;
    if (!complex) totalsamples *=2;
  }
  if (udp.enabled)
    bufsize = udp.size-header_bytes;
  else 
    bufsize=framesize;

  // Need to to have an integral number of frames/sec
  while (bufsize>0) {
    if ((int)(datarate*1e6) % bufsize==0) break;
    bufsize -= 8;
  }
  if (bufsize==0) {
    fprintf(stderr, "Could not find frame size to suit %d Mbps\n", datarate);
    exit(1);
  }
  printf("Using data frame size of %d bytes\n", bufsize);
  if (udp.enabled) udp.size = bufsize;

  framepersec =  datarate*1e6/((bufsize-header_bytes)*8);

  mjdsec = llround(mjd*24*60*60);
  for (i=0;i<numthread;i++) {
    if (mode==VDIF) {
      status = createVDIFHeader(&vdif_headers[i], bufsize-header_bytes, i+firstthread, bits, numchan, complex, "Tt");
      if (status!=VDIF_NOERROR) {
	fprintf(stderr, "Error creating vdif header (%d)\n", status);
	exit(1);
      }
      setVDIFEpochMJD(&vdif_headers[i],lround(floor(mjd)));
      setVDIFFrameMJDSec(&vdif_headers[i], mjdsec);
    } else {
      status = createCODIFHeader(&codif_headers[i], bufsize-header_bytes, i+firstthread, 0, bits, numchan,
				 sampleblocklength, 1, totalsamples, complex, "Tt");
      if (status!=CODIF_NOERROR) {
	fprintf(stderr, "Error creating codif header (%d)\n", status);
	exit(1);
      }
      setCODIFEpochMJD(&codif_headers[i],lround(floor(mjd)));
      setCODIFFrameMJDSec(&codif_headers[i], mjdsec);
    }
  }

  if (strlen(hostname) == 0) {
    strcpy(hostname, "localhost");
  }

  if (updatetime<0.25) updatetime = 0.25;

  duration /= 60*60*24;

  if (rate>0 && udp.enabled) {
    int packetsize = udp.size;
    packetsize -= header_bytes;

    int packetspersec = rate*1e6/8/packetsize;
    udp.usleep = 1/(double)packetspersec;

    printf("Setting sleep between packets to %1.f usec\n", udp.usleep*1e6);
  }


  // Initialise buffers and syncronisation variables
  buf = malloc(bufsize);
  if (buf==NULL) {
    sprintf(msgstr, "Trying to allocate %d KB", bufsize/1024);
    perror(msgstr);
    return(1);
  }

  totalsize=0;

  char start[] = "AAAA        AAAA";
  char end[] = "ZZZZ        ZZZZ";

  memcpy(buf+header_bytes, start, sizeof(start));
  memcpy(buf+bufsize-sizeof(end), end, sizeof(end));
  for (i=header_bytes+sizeof(start);i<bufsize-sizeof(end);i++) {
    buf[i] = (char)(i%256);
  }

  status = setup_net(hostname, port, window_size, &udp, &sock);
  if (status) return(1);

  int framebytes = 0;
  if (udp.enabled)  {
    msg.msg_name       = &udp.dest;
    msg.msg_namelen    = sizeof(udp.dest);
    msg.msg_iov        = &iovect[0];
    if (udp.vtp)
      msg.msg_iovlen     = 3;
    else
      msg.msg_iovlen     = 2;
    msg.msg_control    = 0;
    msg.msg_controllen = 0;
    msg.msg_flags      = 0;

    int k=0;
    if (udp.vtp) {
      iovect[k].iov_base = &udp.sequence;
      iovect[k].iov_len  = sizeof(long long);
      framebytes += sizeof(long long);
      k++;
    }
    iovect[k].iov_len  = header_bytes;
    ihead = k;
    framebytes += header_bytes;
    k++;
    iovect[k].iov_base  = buf;
    iovect[k].iov_len  = bufsize;
    framebytes += bufsize;
  } else {
    // Use iovect for TCP data pointers
    iovect[0].iov_len  = header_bytes;
    framebytes += header_bytes;
    ihead = 0;
    iovect[1].iov_base  = buf;
    iovect[1].iov_len  = bufsize;
    framebytes += bufsize;
  }

  t0 = tim();
  t1 = t0;

  totalpackets = 0;
  bwrote = 0;
  currentthread=0;
  while (mjd+0.001/60/60/24<finishmjd) {
    
    if (mode==VDIF) {
      iovect[ihead].iov_base  = &vdif_headers[currentthread];
    } else if (mode==CODIF) {
      iovect[ihead].iov_base  = &codif_headers[currentthread];
    } 

    // Send data buffer
    totalpackets++;
    if (drop<0 || totalpackets %drop) {
      status = netsend(sock, &msg, iovect, &udp);
      if (status) exit(1);
      bwrote += framebytes;
    }
    t2 = tim();

    if (t2-t1>updatetime) {

      speed = bwrote/(t2-t1)/1.e6*8;
      printf("%6.1f Mbps/s %.1f sec  %5.1f MB\n", speed, t2-t1, bwrote/1e6);

      t1 = t2;
      totalsize += bwrote;
      bwrote = 0;
    }
    
    if (mode==VDIF) {
      nextVDIFHeader(&vdif_headers[currentthread], framepersec);
      currentthread++;
      if (currentthread == numthread) {
        currentthread = 0;
	udp.sequence++;
	mjd = getVDIFFrameDMJD(&vdif_headers[currentthread], framepersec);
      }

    } else if (mode==CODIF) {
      nextCODIFHeader(&codif_headers[currentthread], framepersec);
      currentthread++;
      if (currentthread == numthread) {
        currentthread = 0;
	udp.sequence++;
	mjd = getCODIFFrameDMJD(&codif_headers[currentthread], framepersec);
      }
    }
  }

  totalsize += bwrote;

  t2 = tim();

  status = close(sock);
  if (status!=0) {
    perror("Error closing socket");
  }
    
  speed = totalsize/(t2-t0)/1e6*8;
  printf("\n  Rate = %.2f Mbps/s (%.1f sec)\n\n", speed, t2-t0);

  if (udp.enabled) 
    printf("   Sent %llu packets\n", (unsigned long long)udp.sequence);


  return(0);
}
  
double tim(void) {
  struct timeval tv;
  double t;

  gettimeofday(&tv, NULL);
  t = (double)tv.tv_sec + (double)tv.tv_usec/1000000.0;

  return t;
}

int setup_net(char *hostname, int port, int window_size, udp *udp, int *sock) {
  int status;
  unsigned long ip_addr;
  socklen_t winlen;
  struct hostent     *hostptr;
  struct sockaddr_in server;    /* Socket address */

  hostptr = gethostbyname(hostname);
  if (hostptr==NULL) {
    fprintf(stderr,"Failed to look up hostname %s\n", hostname);
    return(1);
  }
  
  memcpy(&ip_addr, (char *)hostptr->h_addr, sizeof(ip_addr));
  memset((char *) &server, 0, sizeof(server));
  server.sin_family = AF_INET;
  server.sin_port = htons((unsigned short)port); 
  server.sin_addr.s_addr = ip_addr;
      
  if (udp->enabled) {
    *sock = socket(AF_INET,SOCK_DGRAM, IPPROTO_UDP); 
    if (*sock==-1) {
      perror("Failed to allocate UDP socket");
      return(1);
    }
    memcpy(&udp->dest, &server, sizeof(server)); // Save socket for non-connection oriented UDP

  } else {
    printf("Connecting to %s\n",inet_ntoa(server.sin_addr));

    *sock = socket(AF_INET, SOCK_STREAM, 0);
    if (*sock==-1) {
      perror("Failed to allocate socket");
      return(1);
    }
  }

  if (window_size>0) {
    status = setsockopt(*sock, SOL_SOCKET, SO_SNDBUF,
			(char *) &window_size, sizeof(window_size));
    if (status!=0) {
      close(*sock);
      perror("Setting socket options");
      return(1);
    }
    /*
    status = setsockopt(*sock, SOL_SOCKET, SO_RCVBUF,
			  (char *) &window_size, sizeof(window_size));
      if (status!=0) {
	close(*sock);
	perror("Setting socket options");
	return(1);
      }
    */

    /* Check what the window size actually was set to */
    winlen = sizeof(window_size);
    status = getsockopt(*sock, SOL_SOCKET, SO_SNDBUF,
			(char *) &window_size, &winlen);
    if (status!=0) {
      close(*sock);
      perror("Getting socket options");
      return(1);
    }
    printf("Sending socket buffersize set to %d Kbytes\n", window_size/1024);
  }

  if (!udp->enabled) {
    status = connect(*sock, (struct sockaddr *) &server, sizeof(server));
    if (status!=0) {
      perror("Failed to connect to server");
      return(1);
    }
  }
  return(0);
}

int tcpsend(int sock, void *buf, size_t length) {
  void *ptr = buf;
  int nwrote = 0;
  while (length>0) {
    nwrote = send(sock, ptr, length, SENDOPTIONS);
    if (nwrote==-1) {
      if (errno == EINTR) continue;
      perror("Error writing to network");
      return(1);
    } else if (nwrote==0) {
      printf("Warning: Did not write any bytes!\n");
      return(1);
    } else {
      length -= nwrote;
      ptr += nwrote;
    }
  }
  return(0);
}


int netsend(int sock,  struct msghdr *msg, struct iovec *iovect, udp *udp) {
  if (udp->enabled) {
    char str[256];
    ssize_t nsent, ntosend;

    ntosend = 0;
    for (int i=0; i< msg->msg_iovlen; i++) ntosend += iovect[i].iov_len;
    nsent = sendmsg(sock, msg, MSG_EOR);
    if (nsent==-1) {
      sprintf(str, "Sending %d byte UDP packet: ", (int)ntosend);
      perror(str);
      return(1);
    } else if (nsent!=ntosend) {
	printf("Only sent %d of %d bytes for UDP packet\n", (int)nsent, (int)ntosend);
	return(1);
    }

    if (udp->usleep>0) {
      my_usleep(udp);
    }

  } else {
    int status;
  
    status = tcpsend(sock, iovect[0].iov_base, iovect[0].iov_len);
    if (status) return(status);
    status = tcpsend(sock, iovect[0].iov_base, iovect[0].iov_len);
    if (status) return(status);
  }
  return(0);
}

void mjd2cal(double mjd, int *day, int *month, int *year, double *ut) {
  int jd, temp1, temp2;

  *ut = fmod(mjd,1.0);

  if (*ut<0.0) {
    *ut += 1.0;
    mjd -= 1;
  }

  jd = (int)floor(mjd + 2400001);
  //printf("mjd=%.1f\n",mjd);
  //printf("jd=%d\n",jd);

  // Do some rather cryptic calculations
  
  temp1 = 4*(jd+((6*(((4*jd-17918)/146097)))/4+1)/2-37);
  temp2 = 10*(((temp1-237)%1461)/4)+5;

  *year = temp1/1461-4712;
  *month =((temp2/306+2)%12)+1;
  *day = (temp2%306)/10+1;
}

double tm2mjd(struct tm date) {
  int y, c;
  double dayfrac;

  if (date.tm_mon < 2) {
    y = date.tm_mon+1900-1;
  } else {
    y = date.tm_year+1900;
  }

  c = y/100;
  y = y-c*100;

  dayfrac = ((date.tm_hour*60.0+date.tm_min)*60.0+date.tm_sec)
    /(60.0*60.0*24.0);

  return(cal2mjd(date.tm_mday, date.tm_mon+1, date.tm_year+1900)
         +dayfrac);
}

double currentmjd () {
  struct tm *tim;
  struct timeval currenttime;

  gettimeofday(&currenttime, NULL);

  tim = localtime(&currenttime.tv_sec);
  return(tm2mjd(*tim)+(currenttime.tv_usec/1.0e6)/(24.0*60.0*60.0));
}


int turns_to_string(double turns, char type, int dps, int nstr, char str[]) {
  int                 i, hours, sign, whole_turns, dp;
  unsigned long int   revunit, work, scale;
  int                 isec, imin, iunits, right_of_dp;
  char                out[20], text[20], fmt[5];

  /* Record conversion unit and enforce decimal point limits */
  if (type == 'H' || type == 'h') {
    /* Hours mode */
    hours = 1;
    revunit = 86400;
    dp = (dps > 4) ? 4 : dps;
  } else {
    /* Degrees mode */
    hours = 0;
    revunit = 1296000;
    dp = (dps > 3) ? 3 : dps;
  }
  if (dp < 0) dp = 0;
  if (dp != dps) printf("turns_to_string: Invalid number of d.p. requested, "
			"enforcing %d\n", dp);

  /* Record and dispose of sign */
  sign = (turns >= 0) ? 1 : -1;
  turns = turns*(double)sign;

  /* Split into whole number of turns and fractional part. This allows us to
     achieve maximum precision in the seconds part since we can always add
     back a whole number of hours/degrees to the final result without worrying
     about propagation of rounding effects. */
  whole_turns = (int)turns;
  turns -= (double)whole_turns;

  /* Calculate the scaling factor for the conversion */
  i = dp;
  scale = 1.0;
  while (i > 0) {
    scale *= 10;
    i--;
  }
  
  /* Convert - the 0.5 ensures effective rounding, not truncation. */
  work = (unsigned long int)(0.5+(double)(revunit*scale)*turns);

  /* Split off the part to the right of the decimal place */
  right_of_dp = (int)(work - scale*(work/scale));

  /* Convert to seconds and split them off */
  work /= scale;
  isec = (int)(work - 60*(work/60));

  /* Convert to minutes and split them off */
  work /= 60;
  imin = (int)(work - 60*(work/60));

  /* Convert to either hours or degrees and restore whole number of turns */
  iunits = work/60;
  iunits += (hours ? 24 : 360)*whole_turns;

  /* Build the output string */
  out[0] = '\0';
  if (sign == -1) strcat(out, "-");
  sprintf(text, (iunits>99 ? "%d" : "%02d"), iunits);
  strcat(out, text);
  strcat(out ,":");
  sprintf(text, "%02d", imin);
  strcat(out, text);
  strcat(out, ":");
  sprintf(text, "%02d", isec);
  strcat(out, text);
  if (dp > 0) {
    strcat(out, ".");
    sprintf(fmt, "%%0%dd", dp);
    sprintf(text, fmt, right_of_dp);
    strcat(out, text);
  }

  /* Check to see if the output buffer is big enough, if it isn't, make sure
     that the output string is at least null terminated */
  i = strlen(out);
  if (i > nstr) {
    printf("turns_to_string: output string not large enough\n");
    out[nstr-1] = '\0';
    i = nstr-1;
  }
  while (i >= 0) {
    str[i] = out[i];
    i--;
  }

  return(strlen(str));
}

int leap (int year) {
  return (((!(year%4))&&(year%100))||(!(year%400)));
}

static int days[] = {31,28,31,30,31,30,31,31,30,31,30,31};

int cal2dayno(int day, int month, int year) {
  int mon, dayno;

  month--; // For array indexing

  if (leap(year)) {
    days[1] = 29;
  } else {
    days[1] = 28;
  }

  dayno = day;
  for (mon=0; mon<month; mon++) {
    dayno += days[mon];
  }

  return(dayno);
}


void dayno2cal (int dayno, int year, int *day, int *month) {
  int end;

  if (leap(year)) {
    days[1] = 29;
  } else {
    days[1] = 28;
  }

  *month = 0;
  end = days[*month];
  while (dayno>end) {
    (*month)++;
    end+= days[*month];
  }
  end -= days[*month];
  *day = dayno - end;
  (*month)++;

  return;
}

void mjd2dayno(double mjd, int *dayno, int *year, double *ut) {
  int day, month;

  mjd2cal(mjd, &day, &month, year, ut);

  *dayno = cal2dayno(day,month,*year); 
}

static char mjdstr[256];
char* mjd2str(double mjd, int dps) {
  int day, month, year;
  double ut;

  mjd2cal(mjd, &day, &month, &year, &ut);
  turns_to_string(ut, 'H', dps, 256, mjdstr);
  return mjdstr;
}

void my_usleep(udp *udp) {
  double now, till;
  int n;

  /* The time we are sleeping till */
  till = udp->lastsleep+udp->usleep;

  /* The current time */
  now = tim();


  /* and spin for the rest of the time */
  n = 0;
  while (now<till) {
    now = tim();
    n++;
  }
  udp->lastsleep = now;
}

double cal2mjd(int day, int month, int year) {
  int m, y, c, x1, x2, x3;

  if (month <= 2) {
    m = month+9;
    y = year-1;
  } else {
    m = month-3;
    y = year;
  }

  c = y/100;
  y = y-c*100;

  x1 = 146097*c/4;
  x2 = 1461*y/4;
  x3 = (153*m+2)/5;

  return(x1+x2+x3+day-678882);
}
