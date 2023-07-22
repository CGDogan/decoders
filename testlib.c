#include "libbfbridge.h"
#include "graal_isolate.h"

#include <stdio.h>
int main(void) {
    graal_isolate_t *graal_isolate = NULL;
    graal_isolatethread_t *graal_thread = NULL;

       fprintf(stderr, "dddBioFormatsImage.h3: Creating isolate\n");
        int code = graal_create_isolate(NULL, &graal_isolate, &graal_thread);
        fprintf(stderr, "dddBioFormatsImage.h3: Created isolate. should be 0: %d\n", code);
        if (code != 0)
        {
            fprintf(stderr, "dddBioFormatsImage.h3: ERROR But with error!\n");

        }

	//bfinternal_deleteme(graal_thread, "nofile");
        bf_test(graal_thread);
}