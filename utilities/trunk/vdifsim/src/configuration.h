#ifndef __CONFIGURATION_H__
#define __CONFIGURATION_H__

#define MAX_FEATURES	100
#define MAX_FEATURE_TYPE_STRING_LENGTH	32

/* this file contains info supplied by an externa configuration file */

/* keep up to date with const char FeatureTypeStrings[][] in configuration.c */
enum FeatureType
{
	Feature_Gaussian = 0,	/* <center freq> <FWHM> <peak FD> */
	Feature_Sinc,		/* <center freq> <null-to-null width> <peak FD> */
	Feature_Triangle,	/* <center freq> <null-to-null width> <peak FD> */
	Feature_Box,		/* <center freq> <width> <peak FD> */
	Feature_Tone,		/* <freq> <flux> */

	NumFeatureType	/* list terminator */
};

extern const char FeatureTypeStrings[][MAX_FEATURE_TYPE_STRING_LENGTH];


typedef struct
{
	double freq;			/* [MHz] center freq */
	double width;			/* [MHz] full width, somewhat feature dependent */
	union
	{
		double fluxDensity;	/* [Jy] peak flux density (for non-tones) */
		double flux;		/* [Jy.Hz] total tone source flux (for tones) */
	};
	enum FeatureType type;
} Feature;

typedef struct
{
/* source spectrum */
	/* broad-band component */
	double fluxDensity;	/* [Jy] broadband flux density */
	double specIndex;	/* spectral index  S ~ \nu^\alpha */
	double specIndexFreq;	/* [MHz] reference frequency for spectral index */

	/* spectral features */
	Feature features[MAX_FEATURES];
	int nFeature;

/* other settable parameters */

} Configuration;



Configuration *loadConfigration(const char *filename);

Configuration *newConfiguration();

void deleteConfiguration(Configuration *config);

void printConfiguration(const Configuration *config);

#endif
