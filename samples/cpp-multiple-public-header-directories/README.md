Allow configuring multiple headers:

```
library {
  publicHeaders.from('inc-a')
  publicHeaders.from(fileTree('inc-b') { include('**/*.hpp') }) // MAYBE Keep this as another sample
}
```

App

```
#include "A/foo.h"
#include "B/bar.hpp"
```
